package com.telcobright.sipphone.call.events;

import com.telcobright.sipphone.statemachine.GenericEvent;

/** All call-related state machine events. */
public final class CallEvents {

    private CallEvents() {}

    public static class RegisteredEvent extends GenericEvent {
        private final String sessionId;
        public RegisteredEvent(String sessionId) {
            super("REGISTERED", "SIP registration successful");
            this.sessionId = sessionId;
        }
        public String getSessionId() { return sessionId; }
    }

    public static class RegistrationFailedEvent extends GenericEvent {
        public RegistrationFailedEvent(String reason) {
            super("REGISTRATION_FAILED", reason);
        }
    }

    public static class InviteEvent extends GenericEvent {
        private final String destination;
        private final String sdpOffer;
        public InviteEvent(String destination, String sdpOffer) {
            super("INVITE", "Outbound call to " + destination);
            this.destination = destination;
            this.sdpOffer = sdpOffer;
        }
        public String getDestination() { return destination; }
        public String getSdpOffer() { return sdpOffer; }
    }

    public static class IncomingCallEvent extends GenericEvent {
        private final String callId;
        private final String callerNumber;
        private final String remoteSdp;
        public IncomingCallEvent(String callId, String callerNumber, String remoteSdp) {
            super("INCOMING_CALL", "Call from " + callerNumber);
            this.callId = callId;
            this.callerNumber = callerNumber;
            this.remoteSdp = remoteSdp;
        }
        public String getCallId() { return callId; }
        public String getCallerNumber() { return callerNumber; }
        public String getRemoteSdp() { return remoteSdp; }
    }

    public static class RingingEvent extends GenericEvent {
        private final String callId;
        public RingingEvent(String callId) {
            super("RINGING", "Remote ringing");
            this.callId = callId;
        }
        public String getCallId() { return callId; }
    }

    public static class AnsweredEvent extends GenericEvent {
        private final String callId;
        private final String remoteSdp;
        public AnsweredEvent(String callId, String remoteSdp) {
            super("ANSWERED", "Call answered");
            this.callId = callId;
            this.remoteSdp = remoteSdp;
        }
        public String getCallId() { return callId; }
        public String getRemoteSdp() { return remoteSdp; }
    }

    public static class HangupEvent extends GenericEvent {
        private final String callId;
        public HangupEvent(String callId, String reason) {
            super("HANGUP", reason);
            this.callId = callId;
        }
        public String getCallId() { return callId; }
    }

    public static class FailedEvent extends GenericEvent {
        public FailedEvent(String reason) {
            super("FAILED", reason);
        }
    }

    public static class MediaUpdateEvent extends GenericEvent {
        private final String callId;
        private final String remoteSdp;
        public MediaUpdateEvent(String callId, String remoteSdp) {
            super("MEDIA_UPDATE", "SDP update");
            this.callId = callId;
            this.remoteSdp = remoteSdp;
        }
        public String getCallId() { return callId; }
        public String getRemoteSdp() { return remoteSdp; }
    }

    public static class DisconnectedEvent extends GenericEvent {
        public DisconnectedEvent(String reason) {
            super("DISCONNECTED", reason);
        }
    }
}
