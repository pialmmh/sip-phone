package com.telcobright.sipphone.protocol;

import com.telcobright.sipphone.bus.EventBus;
import com.telcobright.sipphone.verto.VertoClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verto signaling bridge — wraps VertoClient, publishes SignalingResult on event bus.
 *
 * Created by VertoRouteSignalingBridge when route is UP.
 * The VertoClient is managed by route health (not by this bridge).
 */
public class VertoSignalingBridge implements SignalingBridge {

    private static final Logger log = LoggerFactory.getLogger(VertoSignalingBridge.class);

    private final VertoClient client;
    private EventBus bus;

    public VertoSignalingBridge(VertoClient client) {
        this.client = client;
    }

    @Override
    public void init(EventBus bus) {
        this.bus = bus;
    }

    /**
     * Install call event listeners on the VertoClient.
     * Called after init() to start receiving call events.
     */
    public void installCallListeners() {
        /* Note: VertoClient's VertoEventListener is set by VertoRouteConnectionHandler.
         * We need to set a callListener on the route health context instead.
         * This is handled by PhoneEngine when wiring things together. */
    }

    @Override
    public String sendInvite(String destination, String sdp) {
        String callId = client.invite(destination, sdp);
        log.debug("Sent INVITE to {} (callId={})", destination, callId);
        return callId;
    }

    @Override
    public void sendAnswer(String callId, String sdp) {
        client.answer(callId, sdp);
        log.debug("Sent ANSWER for {}", callId);
    }

    @Override
    public void sendBye(String callId) {
        client.bye(callId);
        log.debug("Sent BYE for {}", callId);
    }

    @Override
    public void sendDtmf(String callId, String digits) {
        client.sendDtmf(callId, digits);
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.VERTO;
    }

    /**
     * Publish a signaling result on the event bus.
     * Called externally by the route health call listener.
     */
    public void publishResult(SignalingResult result) {
        if (bus != null) {
            bus.publish(result);
        }
    }
}
