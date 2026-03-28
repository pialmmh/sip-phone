package com.telcobright.sipphone.phone;

/**
 * Immutable snapshot of what the UI should render.
 * Published on the event bus by UiStateMachine.
 * UI subscribes and re-renders on each update.
 */
public record UiViewModel(
    /* Registration */
    RegState regState,
    String regMessage,

    /* Call */
    CallUiState callState,
    String callId,
    String remoteNumber,
    String codec,
    String endReason,
    boolean muted,

    /* Quality */
    float packetLoss,
    float jitter,
    float rtt
) {
    public enum RegState { DISCONNECTED, CONNECTING, REGISTERED, FAILED }
    public enum CallUiState { IDLE, DIALING, RINGING, INCOMING, IN_CALL, ENDING }

    /** Convenience: default idle state. */
    public static UiViewModel idle() {
        return new UiViewModel(
            RegState.DISCONNECTED, "", CallUiState.IDLE,
            "", "", "", "", false, 0, 0, 0
        );
    }

    /** Create a copy with modified fields. */
    public UiViewModel withReg(RegState state, String message) {
        return new UiViewModel(state, message, callState, callId, remoteNumber, codec, endReason, muted, packetLoss, jitter, rtt);
    }

    public UiViewModel withCall(CallUiState state) {
        return new UiViewModel(regState, regMessage, state, callId, remoteNumber, codec, endReason, muted, packetLoss, jitter, rtt);
    }

    public UiViewModel withCall(CallUiState state, String callId, String remoteNumber, String codec) {
        return new UiViewModel(regState, regMessage, state, callId, remoteNumber, codec, endReason, muted, packetLoss, jitter, rtt);
    }

    public UiViewModel withEnded(String reason) {
        return new UiViewModel(regState, regMessage, CallUiState.ENDING, "", "", codec, reason, false, packetLoss, jitter, rtt);
    }

    public UiViewModel withMute(boolean muted) {
        return new UiViewModel(regState, regMessage, callState, callId, remoteNumber, codec, endReason, muted, packetLoss, jitter, rtt);
    }
}
