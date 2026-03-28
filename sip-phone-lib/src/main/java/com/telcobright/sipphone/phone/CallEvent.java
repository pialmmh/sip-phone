package com.telcobright.sipphone.phone;

/**
 * Events from CallMachine → UiStateMachine via event bus.
 * Protocol-agnostic — CallMachine translates SignalingResult to these.
 */
public sealed interface CallEvent {
    record Trying(String callId, String destination) implements CallEvent {}
    record Ringing(String callId) implements CallEvent {}
    record Incoming(String callId, String callerNumber) implements CallEvent {}
    record Answered(String callId, String codec) implements CallEvent {}
    record MediaStarted(String callId, String codec) implements CallEvent {}
    record Ended(String callId, String reason) implements CallEvent {}
    record Failed(String callId, String reason) implements CallEvent {}
}
