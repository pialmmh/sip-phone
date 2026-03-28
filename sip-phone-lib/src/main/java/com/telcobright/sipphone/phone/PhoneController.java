package com.telcobright.sipphone.phone;

import com.telcobright.sipphone.protocol.*;
import com.telcobright.sipphone.route.RouteStatus;
import com.telcobright.sipphone.route.health.*;
import com.telcobright.sipphone.route.health.impl.VertoRouteConnectionHandler;
import com.telcobright.sipphone.verto.SdpBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Phone controller — single entry point for UI.
 *
 * Architecture:
 *   UI → PhoneCommand → PhoneController
 *                          ├── RouteHealthRegistry (connection + registration lifecycle)
 *                          ├── CallRouter (selects protocol for call via RouteSignalingBridge)
 *                          └── MediaHandler (platform-specific audio)
 *   UI ← PhoneState ← PhoneController
 *
 * Route UP = WebSocket/TCP connected AND protocol registration succeeded.
 * Route DOWN = connection lost OR registration failed → auto-reconnect.
 *
 * Protocol-agnostic: PhoneController never references VertoClient directly.
 * The CallRouter + RouteSignalingBridge handle protocol selection.
 */
public class PhoneController {

    private static final Logger log = LoggerFactory.getLogger(PhoneController.class);
    private static final String ROUTE_ID = "primary";

    private final PhoneState state = new PhoneState();
    private final List<Consumer<PhoneState>> stateListeners = new CopyOnWriteArrayList<>();

    /* Route health */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "phone-sched"); t.setDaemon(true); return t;
    });
    private final RouteHealthRegistry routeRegistry;
    private final CallRouter callRouter;

    /* Active call signaling (obtained from CallRouter when route is UP) */
    private SignalingAdapter signaling;
    private final SignalingAdapter.SignalingListener callEventHandler = new CallEventHandler();

    /* Media */
    private MediaHandler mediaHandler;
    private int localRtpPort;
    private String localIp;

    public PhoneController() {
        allocatePorts();
        localIp = detectLocalIp();

        routeRegistry = new RouteHealthRegistry(scheduler);
        callRouter = new CallRouter(routeRegistry);

        /* Register protocol handlers and bridges */
        routeRegistry.registerHandler("VERTO", new VertoRouteConnectionHandler());
        callRouter.registerBridge(new VertoRouteSignalingBridge());
        // Future: routeRegistry.registerHandler("SIP", new SipRouteConnectionHandler());
        // Future: callRouter.registerBridge(new SipRouteSignalingBridge());

        /* Listen for route status changes */
        routeRegistry.setRouteStatusListener(this::onRouteStatusChanged);
    }

    public void addStateListener(Consumer<PhoneState> listener) { stateListeners.add(listener); }
    public void removeStateListener(Consumer<PhoneState> listener) { stateListeners.remove(listener); }
    public void setMediaHandler(MediaHandler handler) { this.mediaHandler = handler; }
    public PhoneState getState() { return state; }
    public int getLocalRtpPort() { return localRtpPort; }
    public String getLocalIp() { return localIp; }

    public void execute(PhoneCommand command) {
        log.debug("Command: {}", command);
        switch (command) {
            case PhoneCommand.Register reg -> doRegister(reg);
            case PhoneCommand.Dial dial -> doDial(dial);
            case PhoneCommand.Hangup ignored -> doHangup();
            case PhoneCommand.Answer ans -> doAnswer(ans);
            case PhoneCommand.Reject rej -> doReject(rej);
            case PhoneCommand.SetMute mute -> doMute(mute);
            case PhoneCommand.SendDtmf dtmf -> doDtmf(dtmf);
            case PhoneCommand.Disconnect ignored -> doDisconnect();
        }
    }

    /* === Register: creates route, route health manages lifecycle === */

    private void doRegister(PhoneCommand.Register reg) {
        /* Clean up existing route */
        if (routeRegistry.isRegistered(ROUTE_ID)) {
            routeRegistry.suspendRoute(ROUTE_ID);
            routeRegistry.unregisterRoute(ROUTE_ID);
        }
        signaling = null;

        state.setRegistration(PhoneState.Registration.CONNECTING);
        state.setRegistrationMessage("Connecting...");
        notifyListeners();

        /* Determine protocol from command */
        String protocol = reg.protocol().toUpperCase();

        /* Build route config — protocol-agnostic */
        RouteConfig config = RouteConfig.builder(ROUTE_ID, reg.serverUrl(), protocol)
                .routeName("Primary " + protocol)
                .heartbeatMode(HeartbeatMode.PASSIVE)
                .passiveHeartbeatExpectedIntervalMs(60_000)
                .maxConsecutiveHeartbeatFailures(3)
                .connectTimeoutMs(15_000)
                .autoReconnect(true)
                .reconnectBaseDelayMs(3_000)
                .reconnectMaxDelayMs(30_000)
                .protocolParam("userId", reg.username())
                .protocolParam("password", reg.password())
                .build();

        routeRegistry.registerRoute(config);

        /* Set call event listener on route context */
        RouteHealthContext ctx = routeRegistry.getRouteContext(ROUTE_ID);
        if (ctx != null) {
            ctx.setAttribute("callListener", new VertoRouteConnectionHandler.VertoCallListener() {
                @Override public void onIncomingCall(String callId, String callerNumber, String sdp) {
                    callEventHandler.onIncomingCall(callId, callerNumber, sdp);
                }
                @Override public void onCallAnswered(String callId, String sdp) {
                    callEventHandler.onCallAnswered(callId, sdp);
                }
                @Override public void onCallEnded(String callId, String reason) {
                    callEventHandler.onCallEnded(callId, reason);
                }
                @Override public void onMediaUpdate(String callId, String sdp) {
                    callEventHandler.onCallMedia(callId, sdp);
                }
                @Override public void onError(String error) {
                    callEventHandler.onError(error);
                }
            });
        }

        routeRegistry.startRoute(ROUTE_ID);
    }

    /* === Route status callback — protocol-agnostic === */

    private void onRouteStatusChanged(String routeId, RouteStatus status) {
        if (!ROUTE_ID.equals(routeId)) return;

        if (status == RouteStatus.UP) {
            /* Route UP = connected + registered. Get signaling via CallRouter. */
            signaling = callRouter.getSignalingForRoute(ROUTE_ID);
            if (signaling != null) {
                signaling.setListener(callEventHandler);
                state.setRegistration(PhoneState.Registration.REGISTERED);
                state.setRegistrationMessage("Registered");
                log.info("Route UP — signaling ready via {}", signaling.getProtocolName());
            }
            notifyListeners();
        } else if (status == RouteStatus.DOWN) {
            signaling = null;
            state.setRegistration(PhoneState.Registration.CONNECTING);
            state.setRegistrationMessage("Reconnecting...");
            if (state.getCall() == PhoneState.Call.ANSWERED) {
                stopMedia();
                state.setCall(PhoneState.Call.ENDED);
                state.setEndReason("Route disconnected");
            }
            notifyListeners();
        } else if (status == RouteStatus.SUSPENDED) {
            signaling = null;
            state.setRegistration(PhoneState.Registration.DISCONNECTED);
            state.setRegistrationMessage("Disconnected");
            notifyListeners();
        }
    }

    /* === Call commands === */

    private void doDial(PhoneCommand.Dial dial) {
        if (signaling == null || !callRouter.isRouteAvailable(ROUTE_ID)) {
            log.warn("Cannot dial — route not available");
            return;
        }

        String sdp = SdpBuilder.buildOffer(localIp, localRtpPort, dial.codec());
        String callId = signaling.invite(dial.destination(), sdp);

        state.setCall(PhoneState.Call.TRYING);
        state.setCallId(callId);
        state.setRemoteNumber(dial.destination());
        state.setCodec(dial.codec());
        state.setCallStartMs(System.currentTimeMillis());
        state.setPendingSdp("");
        notifyListeners();
    }

    private void doHangup() {
        if (signaling != null && state.getCallId() != null && !state.getCallId().isEmpty()) {
            signaling.bye(state.getCallId());
        }
        stopMedia();
        state.setCall(PhoneState.Call.ENDED);
        state.setEndReason("User hangup");
        state.setCallId("");
        notifyListeners();
        state.setCall(PhoneState.Call.IDLE);
    }

    private void doAnswer(PhoneCommand.Answer ans) {
        if (signaling == null) return;
        String remoteSdp = state.getPendingSdp();
        String answerSdp = SdpBuilder.buildAnswer(localIp, localRtpPort, remoteSdp, state.getCodec());
        signaling.answer(ans.callId(), answerSdp);
        startMedia(remoteSdp);
        state.setCall(PhoneState.Call.ANSWERED);
        state.setAnswerMs(System.currentTimeMillis());
        notifyListeners();
    }

    private void doReject(PhoneCommand.Reject rej) {
        if (signaling != null) signaling.bye(rej.callId());
        state.setCall(PhoneState.Call.IDLE);
        state.setCallId("");
        notifyListeners();
    }

    private void doMute(PhoneCommand.SetMute mute) {
        if (mediaHandler != null) mediaHandler.setMuted(mute.muted());
    }

    private void doDtmf(PhoneCommand.SendDtmf dtmf) {
        if (signaling != null && !state.getCallId().isEmpty()) {
            signaling.sendDtmf(state.getCallId(), dtmf.digits());
        }
    }

    private void doDisconnect() {
        doHangup();
        if (routeRegistry.isRegistered(ROUTE_ID)) {
            routeRegistry.suspendRoute(ROUTE_ID);
        }
        signaling = null;
        state.setRegistration(PhoneState.Registration.DISCONNECTED);
        state.setRegistrationMessage("Disconnected");
        notifyListeners();
    }

    /* === Call event handler — receives events from protocol layer === */

    private class CallEventHandler implements SignalingAdapter.SignalingListener {
        @Override public void onRegistered(String sessionId) { /* Handled by route health */ }
        @Override public void onRegistrationFailed(String reason) { /* Handled by route health */ }
        @Override public void onDisconnected(String reason) { /* Handled by route health */ }

        @Override public void onIncomingCall(String callId, String callerNumber, String remoteSdp) {
            state.setCallId(callId);
            state.setRemoteNumber(callerNumber);
            state.setPendingSdp(remoteSdp);
            state.setCall(PhoneState.Call.INCOMING);
            notifyListeners();
        }

        @Override public void onCallProgress(String callId) {
            state.setCall(PhoneState.Call.RINGING);
            notifyListeners();
        }

        @Override public void onCallMedia(String callId, String remoteSdp) {
            if (state.getCall() == PhoneState.Call.ANSWERED) {
                stopMedia();
                startMedia(remoteSdp);
            } else {
                state.setPendingSdp(remoteSdp);
                state.setCall(PhoneState.Call.RINGING);
            }
            notifyListeners();
        }

        @Override public void onCallAnswered(String callId, String remoteSdp) {
            String sdp = (remoteSdp != null && !remoteSdp.isEmpty()) ? remoteSdp : state.getPendingSdp();
            if (sdp != null && !sdp.isEmpty()) startMedia(sdp);
            state.setCall(PhoneState.Call.ANSWERED);
            state.setAnswerMs(System.currentTimeMillis());
            notifyListeners();
        }

        @Override public void onCallEnded(String callId, String reason) {
            stopMedia();
            state.setCall(PhoneState.Call.ENDED);
            state.setEndReason(reason);
            state.setCallId("");
            notifyListeners();
        }

        @Override public void onError(String error) {
            log.error("Signaling error: {}", error);
            notifyListeners();
        }
    }

    /* === Media === */

    private void startMedia(String remoteSdp) {
        SdpBuilder.SdpMediaInfo info = SdpBuilder.parseRemoteSdp(remoteSdp);
        if (info == null) { log.error("Cannot parse remote SDP"); return; }
        state.setCodec(info.codecName());
        if (mediaHandler != null) {
            mediaHandler.startMedia(info.remoteIp(), info.remoteRtpPort(), info.remoteRtcpPort(),
                    localRtpPort, info.payloadType(), info.codecType(), info.codecName());
        }
    }

    private void stopMedia() {
        if (mediaHandler != null) mediaHandler.stopMedia();
    }

    private void notifyListeners() {
        for (Consumer<PhoneState> l : stateListeners) {
            try { l.accept(state); } catch (Exception e) { log.error("Listener error", e); }
        }
    }

    private void allocatePorts() {
        Random rng = new Random();
        for (int i = 0; i < 50; i++) {
            int port = 10000 + (rng.nextInt(10000) & 0xFFFE);
            try { new DatagramSocket(port).close(); new DatagramSocket(port+1).close(); localRtpPort = port; return; }
            catch (Exception ignored) {}
        }
        localRtpPort = 10000;
    }

    private String detectLocalIp() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName("8.8.8.8"), 10002);
            return s.getLocalAddress().getHostAddress();
        } catch (Exception e) { return "0.0.0.0"; }
    }

    public interface MediaHandler {
        void startMedia(String remoteIp, int remoteRtpPort, int remoteRtcpPort,
                        int localRtpPort, int payloadType, int codecType, String codecName);
        void stopMedia();
        void setMuted(boolean muted);
    }
}
