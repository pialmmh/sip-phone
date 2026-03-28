package com.telcobright.sipphone.protocol;

/**
 * Events from SignalingBridge → CallMachine via event bus.
 * Protocol-specific details carried as strings — CallMachine doesn't parse them.
 */
public sealed interface SignalingResult {
    /** Call created and invite sent. */
    record Trying(String callId) implements SignalingResult {}

    /** Remote party ringing / early media. */
    record Progress(String callId, String remoteSdp) implements SignalingResult {}

    /** SDP received (verto.media / SIP 183). May arrive before or with Answered. */
    record Media(String callId, String remoteSdp) implements SignalingResult {}

    /** Call answered — media should start. */
    record Answered(String callId, String remoteSdp) implements SignalingResult {}

    /** Call ended normally. */
    record Ended(String callId, String reason) implements SignalingResult {}

    /** Call failed — may trigger re-route. */
    record Failed(String callId, String reason, int causeCode) implements SignalingResult {}

    /** Incoming call from remote. */
    record Incoming(String callId, String callerNumber, String remoteSdp) implements SignalingResult {}
}
