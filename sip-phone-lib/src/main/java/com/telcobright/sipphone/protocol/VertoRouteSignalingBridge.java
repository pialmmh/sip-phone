package com.telcobright.sipphone.protocol;

import com.telcobright.sipphone.route.health.RouteHealthContext;
import com.telcobright.sipphone.verto.VertoClient;

/**
 * Verto bridge: extracts VertoClient from route health context
 * and wraps it in a VertoSignalingAdapter for call signaling.
 *
 * Route is UP only when:
 *   1. WebSocket is connected
 *   2. Verto login (JSON-RPC) succeeded
 *
 * Both conditions are enforced by VertoRouteConnectionHandler.connect()
 * which returns true only after login success.
 */
public class VertoRouteSignalingBridge implements RouteSignalingBridge {

    @Override
    public SignalingAdapter createSignalingAdapter(RouteHealthContext ctx) {
        VertoClient client = ctx.getAttribute("vertoClient");
        if (client == null) return null;
        return new VertoSignalingAdapter(client);
    }

    @Override
    public SignalingBridge createBridge(RouteHealthContext ctx) {
        VertoClient client = ctx.getAttribute("vertoClient");
        if (client == null) return null;
        return new VertoSignalingBridge(client);
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.VERTO;
    }
}
