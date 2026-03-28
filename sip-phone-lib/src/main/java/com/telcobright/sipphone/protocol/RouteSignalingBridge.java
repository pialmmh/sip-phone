package com.telcobright.sipphone.protocol;

import com.telcobright.sipphone.route.health.RouteHealthContext;

/**
 * Bridge between route health and call signaling.
 *
 * Each protocol implements this to:
 * 1. Extract a SignalingAdapter from a healthy route context
 * 2. Define what "registered" means for that protocol
 *
 * Route UP = connection established + protocol registration successful.
 *
 * Implementations:
 *   VertoRouteSignalingBridge: extracts VertoClient from context → VertoSignalingAdapter
 *   SipRouteSignalingBridge:  extracts SIP UA from context → SipSignalingAdapter (future)
 */
public interface RouteSignalingBridge {

    /**
     * Get a SignalingAdapter from a connected route's context.
     * Called when route transitions to CONNECTED.
     *
     * @return adapter for call signaling, or null if context doesn't have the required client
     */
    SignalingAdapter createSignalingAdapter(RouteHealthContext ctx);

    /**
     * Get the protocol type this bridge handles.
     */
    ProtocolType getProtocolType();
}
