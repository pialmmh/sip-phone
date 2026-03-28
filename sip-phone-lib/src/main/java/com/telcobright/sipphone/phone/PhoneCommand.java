package com.telcobright.sipphone.phone;

/**
 * Commands from UI → PhoneController.
 * The only way the UI interacts with the phone.
 */
public sealed interface PhoneCommand {

    record Register(String serverUrl, String username, String password, String protocol) implements PhoneCommand {}

    record Dial(String destination, String codec) implements PhoneCommand {}

    record Hangup() implements PhoneCommand {}

    record Answer(String callId) implements PhoneCommand {}

    record Reject(String callId) implements PhoneCommand {}

    record SetMute(boolean muted) implements PhoneCommand {}

    record SendDtmf(String digits) implements PhoneCommand {}

    record Disconnect() implements PhoneCommand {}
}
