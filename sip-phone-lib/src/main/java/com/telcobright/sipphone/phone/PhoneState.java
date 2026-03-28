package com.telcobright.sipphone.phone;

/**
 * Observable phone state — UI subscribes to this.
 * Updated by PhoneController on every state machine transition.
 */
public class PhoneState {

    public enum Registration { DISCONNECTED, CONNECTING, REGISTERED, FAILED }
    public enum Call { IDLE, TRYING, RINGING, INCOMING, ANSWERED, ENDED }

    private Registration registration = Registration.DISCONNECTED;
    private Call call = Call.IDLE;
    private String registrationMessage = "";
    private String callId = "";
    private String remoteNumber = "";
    private String codec = "";
    private String endReason = "";
    private String pendingSdp = "";
    private long callStartMs;
    private long answerMs;

    /* Quality metrics from RTCP */
    private float packetLossPercent;
    private float jitterMs;
    private float rttMs;

    public Registration getRegistration() { return registration; }
    public void setRegistration(Registration r) { this.registration = r; }

    public Call getCall() { return call; }
    public void setCall(Call c) { this.call = c; }

    public String getRegistrationMessage() { return registrationMessage; }
    public void setRegistrationMessage(String m) { this.registrationMessage = m; }

    public String getCallId() { return callId; }
    public void setCallId(String id) { this.callId = id; }

    public String getRemoteNumber() { return remoteNumber; }
    public void setRemoteNumber(String n) { this.remoteNumber = n; }

    public String getCodec() { return codec; }
    public void setCodec(String c) { this.codec = c; }

    public String getEndReason() { return endReason; }
    public void setEndReason(String r) { this.endReason = r; }

    public String getPendingSdp() { return pendingSdp; }
    public void setPendingSdp(String sdp) { this.pendingSdp = sdp; }

    public long getCallStartMs() { return callStartMs; }
    public void setCallStartMs(long ms) { this.callStartMs = ms; }

    public long getAnswerMs() { return answerMs; }
    public void setAnswerMs(long ms) { this.answerMs = ms; }

    public float getPacketLossPercent() { return packetLossPercent; }
    public void setPacketLossPercent(float v) { this.packetLossPercent = v; }

    public float getJitterMs() { return jitterMs; }
    public void setJitterMs(float v) { this.jitterMs = v; }

    public float getRttMs() { return rttMs; }
    public void setRttMs(float v) { this.rttMs = v; }
}
