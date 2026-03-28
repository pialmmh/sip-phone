package com.telcobright.sipphone.phone;

/**
 * Requests from UiStateMachine → CallMachine via event bus.
 */
public sealed interface CallRequest {
    record Invite(String destination, String codec) implements CallRequest {}
    record Answer(String callId, String codec) implements CallRequest {}
    record Bye(String callId) implements CallRequest {}
    record Dtmf(String callId, String digits) implements CallRequest {}
    record MuteMedia(boolean muted) implements CallRequest {}
}
