package com.telcobright.sipphone.phone;

/**
 * Persistent entity for a single call leg in the state machine.
 * One instance per call — stored in GenericStateMachine.persistingEntity.
 */
public class CallContext {

    private String callId = "";
    private String destination = "";
    private String callerNumber = "";
    private String codec = "";
    private String localSdp = "";
    private String remoteSdp = "";
    private String pendingSdp = "";
    private String endReason = "";
    private long startTimeMs;
    private long answerTimeMs;

    public String getCallId() { return callId; }
    public void setCallId(String v) { this.callId = v; }

    public String getDestination() { return destination; }
    public void setDestination(String v) { this.destination = v; }

    public String getCallerNumber() { return callerNumber; }
    public void setCallerNumber(String v) { this.callerNumber = v; }

    public String getCodec() { return codec; }
    public void setCodec(String v) { this.codec = v; }

    public String getLocalSdp() { return localSdp; }
    public void setLocalSdp(String v) { this.localSdp = v; }

    public String getRemoteSdp() { return remoteSdp; }
    public void setRemoteSdp(String v) { this.remoteSdp = v; }

    public String getPendingSdp() { return pendingSdp; }
    public void setPendingSdp(String v) { this.pendingSdp = v; }

    public String getEndReason() { return endReason; }
    public void setEndReason(String v) { this.endReason = v; }

    public long getStartTimeMs() { return startTimeMs; }
    public void setStartTimeMs(long v) { this.startTimeMs = v; }

    public long getAnswerTimeMs() { return answerTimeMs; }
    public void setAnswerTimeMs(long v) { this.answerTimeMs = v; }
}
