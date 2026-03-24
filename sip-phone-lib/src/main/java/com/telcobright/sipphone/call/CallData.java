package com.telcobright.sipphone.call;

/**
 * Call data — persistent entity for a single call leg.
 */
public class CallData {

    public enum CallDirection { INBOUND, OUTBOUND }

    private String callId = "";
    private String destination = "";
    private String callerNumber = "";
    private String codec = "PCMU";
    private String localSdp = "";
    private String remoteSdp = "";
    private CallDirection direction = CallDirection.OUTBOUND;
    private long startTimeMs;
    private long answerTimeMs;
    private long endTimeMs;
    private String hangupReason = "";

    public String getCallId() { return callId; }
    public void setCallId(String callId) { this.callId = callId; }

    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }

    public String getCallerNumber() { return callerNumber; }
    public void setCallerNumber(String callerNumber) { this.callerNumber = callerNumber; }

    public String getCodec() { return codec; }
    public void setCodec(String codec) { this.codec = codec; }

    public String getLocalSdp() { return localSdp; }
    public void setLocalSdp(String localSdp) { this.localSdp = localSdp; }

    public String getRemoteSdp() { return remoteSdp; }
    public void setRemoteSdp(String remoteSdp) { this.remoteSdp = remoteSdp; }

    public CallDirection getDirection() { return direction; }
    public void setDirection(CallDirection direction) { this.direction = direction; }

    public long getStartTimeMs() { return startTimeMs; }
    public void setStartTimeMs(long startTimeMs) { this.startTimeMs = startTimeMs; }

    public long getAnswerTimeMs() { return answerTimeMs; }
    public void setAnswerTimeMs(long answerTimeMs) { this.answerTimeMs = answerTimeMs; }

    public long getEndTimeMs() { return endTimeMs; }
    public void setEndTimeMs(long endTimeMs) { this.endTimeMs = endTimeMs; }

    public String getHangupReason() { return hangupReason; }
    public void setHangupReason(String hangupReason) { this.hangupReason = hangupReason; }
}
