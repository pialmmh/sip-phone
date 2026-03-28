package com.telcobright.sipphone.phone;

/**
 * Actions from UI → UiStateMachine. Async, fire-and-forget.
 * UI never blocks waiting for a result.
 */
public sealed interface UiAction {
    record Register(String serverUrl, String username, String password, String protocol) implements UiAction {}
    record Dial(String destination, String codec) implements UiAction {}
    record Hangup() implements UiAction {}
    record Answer(String callId) implements UiAction {}
    record Reject(String callId) implements UiAction {}
    record Mute(boolean muted) implements UiAction {}
    record SendDtmf(String digits) implements UiAction {}
    record Disconnect() implements UiAction {}
}
