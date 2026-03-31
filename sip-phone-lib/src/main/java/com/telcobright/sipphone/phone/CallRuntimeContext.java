package com.telcobright.sipphone.phone;

import com.telcobright.sipphone.bus.EventBus;
import com.telcobright.sipphone.protocol.SignalingBridge;

/**
 * Volatile runtime context for a call leg.
 * Not persisted — holds references to signaling bridge, media handler, event bus.
 * Stored in GenericStateMachine.context.
 */
public class CallRuntimeContext {

    private final EventBus bus;
    private final CallRouter callRouter;
    private final MediaHandler mediaHandler;
    private final String routeId;
    private final String localIp;
    private final int localRtpPort;

    private SignalingBridge bridge;

    public CallRuntimeContext(EventBus bus, CallRouter callRouter, MediaHandler mediaHandler,
                              String routeId, String localIp, int localRtpPort) {
        this.bus = bus;
        this.callRouter = callRouter;
        this.mediaHandler = mediaHandler;
        this.routeId = routeId;
        this.localIp = localIp;
        this.localRtpPort = localRtpPort;
    }

    public EventBus getBus() { return bus; }
    public CallRouter getCallRouter() { return callRouter; }
    public MediaHandler getMediaHandler() { return mediaHandler; }
    public String getRouteId() { return routeId; }
    public String getLocalIp() { return localIp; }
    public int getLocalRtpPort() { return localRtpPort; }

    public SignalingBridge getBridge() { return bridge; }
    public void setBridge(SignalingBridge v) { this.bridge = v; }
}
