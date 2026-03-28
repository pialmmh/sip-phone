package com.telcobright.sipphone.phone;

import com.telcobright.sipphone.bus.EventBus;
import com.telcobright.sipphone.protocol.SignalingBridge;
import com.telcobright.sipphone.protocol.SignalingResult;
import com.telcobright.sipphone.verto.SdpBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Protocol-agnostic call state machine.
 *
 * States: IDLE → ROUTING → TRYING → PROGRESS → ANSWERED → COMPLETED / FAILED
 *
 * Receives: CallRequest (from UiStateMachine via bus)
 *           SignalingResult (from SignalingBridge via bus)
 * Publishes: CallEvent (to UiStateMachine via bus)
 *
 * Like a simplified GPM — just: route → signal → media → result.
 * No billing, no CDR, no multi-tenant. Clean single responsibility.
 */
public class CallMachine {

    private static final Logger log = LoggerFactory.getLogger(CallMachine.class);

    public enum State { IDLE, ROUTING, TRYING, PROGRESS, ANSWERED, COMPLETED, FAILED }

    private final EventBus bus;
    private final CallRouter callRouter;
    private final String routeId;

    /* Media handler — platform-specific */
    private MediaHandler mediaHandler;

    /* Current call state */
    private volatile State state = State.IDLE;
    private String callId;
    private String destination;
    private String codec;
    private String pendingSdp;
    private String localIp;
    private int localRtpPort;

    /* Active signaling bridge for current call */
    private SignalingBridge bridge;

    public CallMachine(EventBus bus, CallRouter callRouter, String routeId,
                       String localIp, int localRtpPort) {
        this.bus = bus;
        this.callRouter = callRouter;
        this.routeId = routeId;
        this.localIp = localIp;
        this.localRtpPort = localRtpPort;

        /* Subscribe to events */
        bus.subscribe(CallRequest.class, this::onCallRequest);
        bus.subscribe(SignalingResult.class, this::onSignalingResult);
    }

    public void setMediaHandler(MediaHandler handler) {
        this.mediaHandler = handler;
    }

    public State getState() { return state; }

    /* === CallRequest handlers === */

    private void onCallRequest(CallRequest req) {
        switch (req) {
            case CallRequest.Invite inv -> handleInvite(inv);
            case CallRequest.Answer ans -> handleAnswer(ans);
            case CallRequest.Bye bye -> handleBye(bye);
            case CallRequest.Dtmf dtmf -> handleDtmf(dtmf);
            case CallRequest.MuteMedia mute -> handleMute(mute);
        }
    }

    private void handleInvite(CallRequest.Invite inv) {
        if (state != State.IDLE) {
            log.warn("Cannot invite in state {}", state);
            return;
        }

        destination = inv.destination();
        codec = inv.codec();
        pendingSdp = null;

        /* ROUTING: get signaling bridge from route */
        state = State.ROUTING;
        bridge = callRouter.getBridgeForRoute(routeId);
        if (bridge == null) {
            log.error("No signaling bridge for route {}", routeId);
            state = State.FAILED;
            bus.publish(new CallEvent.Failed("", "No route available"));
            state = State.IDLE;
            return;
        }
        bridge.init(bus);

        /* TRYING: build SDP and send invite */
        String sdp = SdpBuilder.buildOffer(localIp, localRtpPort, codec);
        callId = bridge.sendInvite(destination, sdp);

        state = State.TRYING;
        bus.publish(new CallEvent.Trying(callId, destination));
        log.info("Call {} → TRYING (dest={}, codec={})", callId, destination, codec);
    }

    private void handleAnswer(CallRequest.Answer ans) {
        if (bridge == null) return;
        String remoteSdp = pendingSdp != null ? pendingSdp : "";
        String answerSdp = SdpBuilder.buildAnswer(localIp, localRtpPort, remoteSdp, codec);
        bridge.sendAnswer(ans.callId(), answerSdp);
        startMedia(remoteSdp);
        state = State.ANSWERED;
        bus.publish(new CallEvent.Answered(ans.callId(), codec));
    }

    private void handleBye(CallRequest.Bye bye) {
        if (bridge != null && bye.callId() != null && !bye.callId().isEmpty()) {
            bridge.sendBye(bye.callId());
        }
        stopMedia();
        state = State.COMPLETED;
        bus.publish(new CallEvent.Ended(bye.callId(), "User hangup"));
        reset();
    }

    private void handleDtmf(CallRequest.Dtmf dtmf) {
        if (bridge != null) bridge.sendDtmf(dtmf.callId(), dtmf.digits());
    }

    private void handleMute(CallRequest.MuteMedia mute) {
        if (mediaHandler != null) mediaHandler.setMuted(mute.muted());
    }

    /* === SignalingResult handlers === */

    private void onSignalingResult(SignalingResult result) {
        switch (result) {
            case SignalingResult.Media media -> handleMedia(media);
            case SignalingResult.Answered ans -> handleAnswered(ans);
            case SignalingResult.Ended ended -> handleEnded(ended);
            case SignalingResult.Failed failed -> handleFailed(failed);
            case SignalingResult.Incoming incoming -> handleIncoming(incoming);
            case SignalingResult.Progress progress -> handleProgress(progress);
            case SignalingResult.Trying trying -> { /* already in TRYING */ }
        }
    }

    private void handleMedia(SignalingResult.Media media) {
        if (state == State.ANSWERED) {
            /* Mid-call media update */
            stopMedia();
            startMedia(media.remoteSdp());
        } else {
            /* Pre-answer SDP */
            pendingSdp = media.remoteSdp();
            if (state == State.TRYING) {
                state = State.PROGRESS;
                bus.publish(new CallEvent.Ringing(media.callId()));
            }
        }
    }

    private void handleProgress(SignalingResult.Progress progress) {
        if (state == State.TRYING) {
            pendingSdp = progress.remoteSdp();
            state = State.PROGRESS;
            bus.publish(new CallEvent.Ringing(progress.callId()));
        }
    }

    private void handleAnswered(SignalingResult.Answered ans) {
        String sdp = (ans.remoteSdp() != null && !ans.remoteSdp().isEmpty())
                     ? ans.remoteSdp() : pendingSdp;
        if (sdp != null && !sdp.isEmpty()) {
            startMedia(sdp);
        }
        state = State.ANSWERED;
        String resolvedCodec = resolveCodec(sdp);
        bus.publish(new CallEvent.Answered(ans.callId(), resolvedCodec));
        log.info("Call {} → ANSWERED (codec={})", ans.callId(), resolvedCodec);
    }

    private void handleEnded(SignalingResult.Ended ended) {
        stopMedia();
        state = State.COMPLETED;
        bus.publish(new CallEvent.Ended(ended.callId(), ended.reason()));
        log.info("Call {} → ENDED ({})", ended.callId(), ended.reason());
        reset();
    }

    private void handleFailed(SignalingResult.Failed failed) {
        stopMedia();
        state = State.FAILED;
        bus.publish(new CallEvent.Failed(failed.callId(), failed.reason()));
        log.info("Call {} → FAILED ({}, code={})", failed.callId(), failed.reason(), failed.causeCode());
        reset();
    }

    private void handleIncoming(SignalingResult.Incoming incoming) {
        callId = incoming.callId();
        pendingSdp = incoming.remoteSdp();
        state = State.PROGRESS;
        bus.publish(new CallEvent.Incoming(incoming.callId(), incoming.callerNumber()));
    }

    /* === Media === */

    private void startMedia(String remoteSdp) {
        SdpBuilder.SdpMediaInfo info = SdpBuilder.parseRemoteSdp(remoteSdp);
        if (info == null) { log.error("Cannot parse SDP"); return; }
        codec = info.codecName();
        if (mediaHandler != null) {
            mediaHandler.startMedia(info.remoteIp(), info.remoteRtpPort(), info.remoteRtcpPort(),
                    localRtpPort, info.payloadType(), info.codecType(), info.codecName());
        }
        bus.publish(new CallEvent.MediaStarted(callId, info.codecName()));
    }

    private void stopMedia() {
        if (mediaHandler != null) mediaHandler.stopMedia();
    }

    private String resolveCodec(String sdp) {
        if (sdp == null) return codec;
        SdpBuilder.SdpMediaInfo info = SdpBuilder.parseRemoteSdp(sdp);
        return info != null ? info.codecName() : codec;
    }

    private void reset() {
        callId = null;
        destination = null;
        pendingSdp = null;
        bridge = null;
        state = State.IDLE;
    }
}
