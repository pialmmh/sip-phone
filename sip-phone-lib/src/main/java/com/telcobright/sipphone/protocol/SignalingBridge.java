package com.telcobright.sipphone.protocol;

import com.telcobright.sipphone.bus.EventBus;

/**
 * Protocol-specific signaling bridge — async, event-driven.
 *
 * All methods are fire-and-forget. Results come back as SignalingResult
 * published on the event bus.
 *
 * Implementations:
 *   VertoSignalingBridge  — WebSocket JSON-RPC
 *   SipSignalingBridge    — SIP INVITE/BYE (future)
 */
public interface SignalingBridge {

    /**
     * Initialize bridge with event bus for publishing results.
     */
    void init(EventBus bus);

    /**
     * Send outbound call invite. Results arrive as SignalingResult on event bus.
     * @return callId
     */
    String sendInvite(String destination, String sdp);

    /**
     * Answer incoming call.
     */
    void sendAnswer(String callId, String sdp);

    /**
     * Hang up a call.
     */
    void sendBye(String callId);

    /**
     * Send DTMF.
     */
    void sendDtmf(String callId, String digits);

    /**
     * Get protocol type.
     */
    ProtocolType getProtocolType();
}
