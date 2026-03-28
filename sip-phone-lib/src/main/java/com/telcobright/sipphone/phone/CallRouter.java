package com.telcobright.sipphone.phone;

import com.telcobright.sipphone.protocol.ProtocolType;
import com.telcobright.sipphone.protocol.RouteSignalingBridge;
import com.telcobright.sipphone.protocol.SignalingAdapter;
import com.telcobright.sipphone.protocol.SignalingBridge;
import com.telcobright.sipphone.route.health.RouteHealthContext;
import com.telcobright.sipphone.route.health.RouteHealthRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes calls to the appropriate protocol handler.
 *
 * UI → PhoneCommand.Dial → CallRouter.getSignalingForRoute(routeId)
 *   → returns SignalingAdapter (Verto or SIP) from the healthy route
 *
 * Each route has a protocol type (VERTO, SIP). The router uses the
 * matching RouteSignalingBridge to extract a SignalingAdapter from
 * the route's health context.
 */
public class CallRouter {

    private static final Logger log = LoggerFactory.getLogger(CallRouter.class);

    private final RouteHealthRegistry routeRegistry;
    private final Map<ProtocolType, RouteSignalingBridge> bridges = new ConcurrentHashMap<>();

    public CallRouter(RouteHealthRegistry routeRegistry) {
        this.routeRegistry = routeRegistry;
    }

    /**
     * Register a protocol bridge.
     */
    public void registerBridge(RouteSignalingBridge bridge) {
        bridges.put(bridge.getProtocolType(), bridge);
        log.info("Registered signaling bridge: {}", bridge.getProtocolType());
    }

    /**
     * Get a SignalingAdapter for a route, if the route is UP and registered.
     *
     * @return SignalingAdapter or null if route is down/unavailable
     */
    public SignalingAdapter getSignalingForRoute(String routeId) {
        if (!routeRegistry.isAvailable(routeId)) {
            log.debug("Route {} not available", routeId);
            return null;
        }

        RouteHealthContext ctx = routeRegistry.getRouteContext(routeId);
        if (ctx == null) return null;

        ProtocolType protocol = ProtocolType.valueOf(ctx.getProtocolName().toUpperCase());
        RouteSignalingBridge bridge = bridges.get(protocol);
        if (bridge == null) {
            log.error("No signaling bridge for protocol: {}", protocol);
            return null;
        }

        SignalingAdapter adapter = bridge.createSignalingAdapter(ctx);
        if (adapter == null) {
            log.warn("Bridge returned null adapter for route {} — route UP but client missing?", routeId);
        }
        return adapter;
    }

    /**
     * Check if a route is available for calls.
     */
    public boolean isRouteAvailable(String routeId) {
        return routeRegistry.isAvailable(routeId);
    }

    /**
     * Get a SignalingBridge for a route (new event-driven interface).
     * Used by CallMachine.
     */
    public SignalingBridge getBridgeForRoute(String routeId) {
        if (!routeRegistry.isAvailable(routeId)) {
            log.debug("Route {} not available", routeId);
            return null;
        }

        RouteHealthContext ctx = routeRegistry.getRouteContext(routeId);
        if (ctx == null) return null;

        ProtocolType protocol = ProtocolType.valueOf(ctx.getProtocolName().toUpperCase());
        RouteSignalingBridge bridge = bridges.get(protocol);
        if (bridge == null) {
            log.error("No signaling bridge for protocol: {}", protocol);
            return null;
        }

        return bridge.createBridge(ctx);
    }
}
