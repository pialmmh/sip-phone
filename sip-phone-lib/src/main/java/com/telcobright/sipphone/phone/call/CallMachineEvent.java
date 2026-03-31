package com.telcobright.sipphone.phone.call;

import com.telcobright.sipphone.statemachine.GenericEvent;

/**
 * Events fired into call state machines.
 * Each event type maps to a transition or stay action.
 */
public class CallMachineEvent extends GenericEvent {

    public enum Type {
        INVITE,             // Outbound invite request
        INCOMING,           // Inbound call received
        MEDIA,              // SDP received (pre-answer or mid-call)
        PROGRESS,           // Remote ringing / early media
        ANSWERED,           // Call answered
        ENDED,              // Normal hangup
        FAILED,             // Call failed
        BYE,                // User hangup
        MUTE                // Mute toggle
    }

    private final Type type;
    private final String callId;
    private final String sdp;
    private final String destination;
    private final String callerNumber;
    private final String reason;
    private final int causeCode;

    public CallMachineEvent(Type type) {
        this(type, "", "", "", "", "", 0);
    }

    public CallMachineEvent(Type type, String callId) {
        this(type, callId, "", "", "", "", 0);
    }

    public CallMachineEvent(Type type, String callId, String sdp) {
        this(type, callId, sdp, "", "", "", 0);
    }

    public CallMachineEvent(Type type, String callId, String sdp,
                            String destination, String callerNumber, String reason, int causeCode) {
        super(type.name(), reason);
        this.type = type;
        this.callId = callId;
        this.sdp = sdp;
        this.destination = destination;
        this.callerNumber = callerNumber;
        this.reason = reason;
        this.causeCode = causeCode;
    }

    public Type getType() { return type; }
    public String getCallId() { return callId; }
    public String getSdp() { return sdp; }
    public String getDestination() { return destination; }
    public String getCallerNumber() { return callerNumber; }
    public String getReason() { return reason; }
    public int getCauseCode() { return causeCode; }

    /* Convenience factories */
    public static CallMachineEvent invite(String destination, String codec) {
        return new CallMachineEvent(Type.INVITE, "", "", destination, "", codec, 0);
    }

    public static CallMachineEvent incoming(String callId, String callerNumber, String sdp) {
        return new CallMachineEvent(Type.INCOMING, callId, sdp, "", callerNumber, "", 0);
    }

    public static CallMachineEvent media(String callId, String sdp) {
        return new CallMachineEvent(Type.MEDIA, callId, sdp);
    }

    public static CallMachineEvent answered(String callId, String sdp) {
        return new CallMachineEvent(Type.ANSWERED, callId, sdp);
    }

    public static CallMachineEvent ended(String callId, String reason) {
        return new CallMachineEvent(Type.ENDED, callId, "", "", "", reason, 0);
    }

    public static CallMachineEvent failed(String callId, String reason, int causeCode) {
        return new CallMachineEvent(Type.FAILED, callId, "", "", "", reason, causeCode);
    }

    public static CallMachineEvent bye(String callId) {
        return new CallMachineEvent(Type.BYE, callId);
    }
}
