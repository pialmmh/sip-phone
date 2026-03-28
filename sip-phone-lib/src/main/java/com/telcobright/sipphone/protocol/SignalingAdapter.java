package com.telcobright.sipphone.protocol;

/**
 * Protocol-agnostic signaling adapter.
 * Implementations: VertoSignalingAdapter, SipSignalingAdapter (future).
 *
 * The adapter translates between protocol-specific events and
 * generic SignalingEvent callbacks.
 */
public interface SignalingAdapter {

    /**
     * Connect and register with the server.
     */
    void connect(String serverUrl, String username, String password);

    /**
     * Disconnect from server.
     */
    void disconnect();

    /**
     * Send outbound call invite.
     * @return call ID
     */
    String invite(String destination, String sdp);

    /**
     * Answer incoming call.
     */
    void answer(String callId, String sdp);

    /**
     * Hang up a call.
     */
    void bye(String callId);

    /**
     * Send DTMF.
     */
    void sendDtmf(String callId, String digits);

    /**
     * Set event listener.
     */
    void setListener(SignalingListener listener);

    /**
     * Get protocol name.
     */
    String getProtocolName();

    /**
     * Is currently registered/connected.
     */
    boolean isRegistered();

    /**
     * Protocol-agnostic signaling events — same for Verto, SIP, etc.
     */
    interface SignalingListener {
        void onRegistered(String sessionId);
        void onRegistrationFailed(String reason);
        void onDisconnected(String reason);
        void onIncomingCall(String callId, String callerNumber, String remoteSdp);
        void onCallProgress(String callId);                    // 180 Ringing / early media
        void onCallAnswered(String callId, String remoteSdp);  // 200 OK with SDP
        void onCallMedia(String callId, String remoteSdp);     // SDP update (re-INVITE / verto.media)
        void onCallEnded(String callId, String reason);
        void onError(String error);
    }
}
