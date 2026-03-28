package com.telcobright.sipphone.protocol;

import com.telcobright.sipphone.verto.VertoClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verto signaling adapter — maps Verto WebSocket JSON-RPC events
 * to protocol-agnostic SignalingListener events.
 *
 * Verto event mapping:
 *   verto login success    → onRegistered
 *   verto login failed     → onRegistrationFailed
 *   WebSocket closed       → onDisconnected
 *   verto.invite (inbound) → onIncomingCall
 *   verto.media            → onCallMedia (early SDP, before answer)
 *   verto.answer           → onCallAnswered
 *   verto.bye              → onCallEnded
 *
 * SIP equivalent (for future SipSignalingAdapter):
 *   200 OK (REGISTER)      → onRegistered
 *   401/403                → onRegistrationFailed
 *   INVITE (inbound)       → onIncomingCall
 *   180 Ringing            → onCallProgress
 *   183 Session Progress   → onCallMedia (early media SDP)
 *   200 OK (INVITE)        → onCallAnswered
 *   BYE                    → onCallEnded
 */
public class VertoSignalingAdapter implements SignalingAdapter {

    private static final Logger log = LoggerFactory.getLogger(VertoSignalingAdapter.class);

    private VertoClient vertoClient;
    private SignalingListener listener;
    private volatile boolean registered;
    private boolean externalClient; // true if VertoClient is managed externally (by route health)

    /** Default constructor — manages its own VertoClient. */
    public VertoSignalingAdapter() {}

    /**
     * Constructor with externally-managed VertoClient (from route health).
     * The adapter uses this client for signaling but doesn't own its lifecycle.
     */
    public VertoSignalingAdapter(VertoClient existingClient) {
        this.vertoClient = existingClient;
        this.externalClient = true;
        this.registered = true;
    }

    @Override
    public void setListener(SignalingListener listener) {
        this.listener = listener;
    }

    @Override
    public void connect(String serverUrl, String username, String password) {
        if (vertoClient != null) {
            vertoClient.disconnect();
        }

        vertoClient = new VertoClient(serverUrl, username, password, new VertoClient.VertoEventListener() {
            @Override
            public void onConnected() {
                log.debug("[Verto] WebSocket connected");
            }

            @Override
            public void onDisconnected(String reason) {
                registered = false;
                if (listener != null) listener.onDisconnected(reason);
            }

            @Override
            public void onLoginSuccess(String sessionId) {
                registered = true;
                if (listener != null) listener.onRegistered(sessionId);
            }

            @Override
            public void onLoginFailed(String error) {
                registered = false;
                if (listener != null) listener.onRegistrationFailed(error);
            }

            @Override
            public void onIncomingCall(String callId, String callerNumber, String sdp) {
                if (listener != null) listener.onIncomingCall(callId, callerNumber, sdp);
            }

            @Override
            public void onCallAnswered(String callId, String sdp) {
                if (listener != null) listener.onCallAnswered(callId, sdp);
            }

            @Override
            public void onCallEnded(String callId, String reason) {
                if (listener != null) listener.onCallEnded(callId, reason);
            }

            @Override
            public void onMediaUpdate(String callId, String sdp) {
                /* Verto sends verto.media before verto.answer — maps to onCallMedia */
                if (listener != null) listener.onCallMedia(callId, sdp);
            }

            @Override
            public void onError(String error) {
                if (listener != null) listener.onError(error);
            }
        });

        vertoClient.connect();
    }

    @Override
    public void disconnect() {
        if (vertoClient != null && !externalClient) {
            vertoClient.disconnect();
            vertoClient = null;
        }
        registered = false;
    }

    @Override
    public String invite(String destination, String sdp) {
        if (vertoClient == null) return null;
        return vertoClient.invite(destination, sdp);
    }

    @Override
    public void answer(String callId, String sdp) {
        if (vertoClient != null) vertoClient.answer(callId, sdp);
    }

    @Override
    public void bye(String callId) {
        if (vertoClient != null) vertoClient.bye(callId);
    }

    @Override
    public void sendDtmf(String callId, String digits) {
        if (vertoClient != null) vertoClient.sendDtmf(callId, digits);
    }

    @Override
    public String getProtocolName() {
        return "VERTO";
    }

    @Override
    public boolean isRegistered() {
        return registered;
    }
}
