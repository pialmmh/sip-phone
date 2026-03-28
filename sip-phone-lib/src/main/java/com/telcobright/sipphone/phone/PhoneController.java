package com.telcobright.sipphone.phone;

import com.telcobright.sipphone.protocol.SignalingAdapter;
import com.telcobright.sipphone.protocol.VertoSignalingAdapter;
import com.telcobright.sipphone.verto.SdpBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Phone controller — the single entry point for UI.
 *
 * UI sends PhoneCommand → controller dispatches to signaling adapter + media.
 * Controller fires PhoneState changes → UI subscribes and updates display.
 *
 * Decoupled layers:
 *   UI → PhoneCommand → PhoneController → SignalingAdapter (Verto/SIP)
 *   UI ← PhoneState  ← PhoneController ← SignalingAdapter events
 */
public class PhoneController implements SignalingAdapter.SignalingListener {

    private static final Logger log = LoggerFactory.getLogger(PhoneController.class);

    private final PhoneState state = new PhoneState();
    private final List<Consumer<PhoneState>> stateListeners = new CopyOnWriteArrayList<>();

    private SignalingAdapter signaling;
    private int localRtpPort;
    private String localIp;

    /* Media start/stop callbacks — platform-specific (Linux Java Sound / Android Oboe) */
    private MediaHandler mediaHandler;

    public PhoneController() {
        allocatePorts();
        localIp = detectLocalIp();
    }

    /**
     * Subscribe to state changes.
     */
    public void addStateListener(Consumer<PhoneState> listener) {
        stateListeners.add(listener);
    }

    public void removeStateListener(Consumer<PhoneState> listener) {
        stateListeners.remove(listener);
    }

    /**
     * Set platform-specific media handler.
     */
    public void setMediaHandler(MediaHandler handler) {
        this.mediaHandler = handler;
    }

    /**
     * Get current state (read-only snapshot).
     */
    public PhoneState getState() {
        return state;
    }

    public int getLocalRtpPort() { return localRtpPort; }
    public String getLocalIp() { return localIp; }

    /**
     * Process a command from the UI.
     */
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

    /* === Command handlers === */

    private void doRegister(PhoneCommand.Register reg) {
        if (signaling != null) signaling.disconnect();

        signaling = switch (reg.protocol().toUpperCase()) {
            case "SIP" -> throw new UnsupportedOperationException("SIP adapter not yet implemented");
            default -> new VertoSignalingAdapter();
        };
        signaling.setListener(this);

        state.setRegistration(PhoneState.Registration.CONNECTING);
        state.setRegistrationMessage("Connecting...");
        notifyListeners();

        signaling.connect(reg.serverUrl(), reg.username(), reg.password());
    }

    private void doDial(PhoneCommand.Dial dial) {
        if (signaling == null || !signaling.isRegistered()) {
            log.warn("Cannot dial — not registered");
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

        /* Reset to IDLE after a moment */
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
        if (signaling != null) {
            signaling.disconnect();
            signaling = null;
        }
        state.setRegistration(PhoneState.Registration.DISCONNECTED);
        state.setRegistrationMessage("Disconnected");
        notifyListeners();
    }

    /* === SignalingAdapter.SignalingListener — protocol events === */

    @Override
    public void onRegistered(String sessionId) {
        state.setRegistration(PhoneState.Registration.REGISTERED);
        state.setRegistrationMessage("Registered (session=" + sessionId + ")");
        notifyListeners();
    }

    @Override
    public void onRegistrationFailed(String reason) {
        state.setRegistration(PhoneState.Registration.FAILED);
        state.setRegistrationMessage("Failed: " + reason);
        notifyListeners();
    }

    @Override
    public void onDisconnected(String reason) {
        state.setRegistration(PhoneState.Registration.DISCONNECTED);
        state.setRegistrationMessage("Disconnected: " + reason);
        if (state.getCall() == PhoneState.Call.ANSWERED) {
            stopMedia();
            state.setCall(PhoneState.Call.ENDED);
            state.setEndReason("Disconnected");
        }
        notifyListeners();
    }

    @Override
    public void onIncomingCall(String callId, String callerNumber, String remoteSdp) {
        state.setCallId(callId);
        state.setRemoteNumber(callerNumber);
        state.setPendingSdp(remoteSdp);
        state.setCall(PhoneState.Call.INCOMING);
        notifyListeners();
    }

    @Override
    public void onCallProgress(String callId) {
        state.setCall(PhoneState.Call.RINGING);
        notifyListeners();
    }

    @Override
    public void onCallMedia(String callId, String remoteSdp) {
        if (state.getCall() == PhoneState.Call.ANSWERED) {
            /* Mid-call re-INVITE */
            stopMedia();
            startMedia(remoteSdp);
        } else {
            /* Pre-answer SDP (verto.media before verto.answer) */
            state.setPendingSdp(remoteSdp);
            state.setCall(PhoneState.Call.RINGING);
        }
        notifyListeners();
    }

    @Override
    public void onCallAnswered(String callId, String remoteSdp) {
        String sdp = (remoteSdp != null && !remoteSdp.isEmpty()) ? remoteSdp : state.getPendingSdp();
        if (sdp != null && !sdp.isEmpty()) {
            startMedia(sdp);
        }
        state.setCall(PhoneState.Call.ANSWERED);
        state.setAnswerMs(System.currentTimeMillis());
        notifyListeners();
    }

    @Override
    public void onCallEnded(String callId, String reason) {
        stopMedia();
        state.setCall(PhoneState.Call.ENDED);
        state.setEndReason(reason);
        state.setCallId("");
        notifyListeners();
    }

    @Override
    public void onError(String error) {
        log.error("Signaling error: {}", error);
        notifyListeners();
    }

    /* === Media === */

    private void startMedia(String remoteSdp) {
        SdpBuilder.SdpMediaInfo info = SdpBuilder.parseRemoteSdp(remoteSdp);
        if (info == null) {
            log.error("Cannot parse remote SDP");
            return;
        }
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
        for (Consumer<PhoneState> listener : stateListeners) {
            try {
                listener.accept(state);
            } catch (Exception e) {
                log.error("State listener error", e);
            }
        }
    }

    /* === Utility === */

    private void allocatePorts() {
        Random rng = new Random();
        for (int i = 0; i < 50; i++) {
            int port = 10000 + (rng.nextInt(10000) & 0xFFFE);
            try {
                new DatagramSocket(port).close();
                new DatagramSocket(port + 1).close();
                localRtpPort = port;
                return;
            } catch (Exception ignored) {}
        }
        localRtpPort = 10000;
    }

    private String detectLocalIp() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName("8.8.8.8"), 10002);
            return s.getLocalAddress().getHostAddress();
        } catch (Exception e) { return "0.0.0.0"; }
    }

    /**
     * Platform-specific media handler.
     * Linux: Java Sound + JNI.  Android: Oboe + JNI.  iOS: Core Audio + C bridge.
     */
    public interface MediaHandler {
        void startMedia(String remoteIp, int remoteRtpPort, int remoteRtcpPort,
                        int localRtpPort, int payloadType, int codecType, String codecName);
        void stopMedia();
        void setMuted(boolean muted);
    }
}
