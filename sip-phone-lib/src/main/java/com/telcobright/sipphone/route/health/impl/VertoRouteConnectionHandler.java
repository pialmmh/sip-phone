package com.telcobright.sipphone.route.health.impl;

import com.telcobright.sipphone.route.health.RouteConnectionHandler;
import com.telcobright.sipphone.route.health.RouteHealthContext;
import com.telcobright.sipphone.verto.VertoClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Verto WebSocket route connection handler.
 *
 * connect() = establish WebSocket + login → route is UP
 * sendHeartbeat() = verify WebSocket alive (responds to verto.ping)
 * disconnect() = close WebSocket
 *
 * Uses our existing VertoClient (OkHttp WebSocket) under the hood.
 */
public class VertoRouteConnectionHandler implements RouteConnectionHandler {

    private static final Logger log = LoggerFactory.getLogger(VertoRouteConnectionHandler.class);

    @Override
    public CompletableFuture<Boolean> connect(RouteHealthContext ctx) {
        String endpoint = ctx.getEndpoint();
        String userId = ctx.getAttribute("userId");
        String password = ctx.getAttribute("password");

        CompletableFuture<Boolean> result = new CompletableFuture<>();

        VertoClient client = new VertoClient(endpoint, userId, password, new VertoClient.VertoEventListener() {
            @Override public void onConnected() {
                log.debug("[VertoRoute] WebSocket connected to {}", endpoint);
            }

            @Override public void onDisconnected(String reason) {
                log.warn("[VertoRoute] Disconnected: {}", reason);
                if (!result.isDone()) result.complete(false);
                // Notify context that connection dropped
                ctx.setAttribute("vertoClient", null);
            }

            @Override public void onLoginSuccess(String sessionId) {
                log.info("[VertoRoute] Registered as {} (session={})", userId, sessionId);
                result.complete(true);
            }

            @Override public void onLoginFailed(String error) {
                log.error("[VertoRoute] Login failed for {}: {}", userId, error);
                result.complete(false);
            }

            @Override public void onIncomingCall(String callId, String callerNumber, String sdp) {
                // Forward to call listener if set
                VertoCallListener listener = ctx.getAttribute("callListener");
                if (listener != null) listener.onIncomingCall(callId, callerNumber, sdp);
            }

            @Override public void onCallAnswered(String callId, String sdp) {
                VertoCallListener listener = ctx.getAttribute("callListener");
                if (listener != null) listener.onCallAnswered(callId, sdp);
            }

            @Override public void onCallEnded(String callId, String reason) {
                VertoCallListener listener = ctx.getAttribute("callListener");
                if (listener != null) listener.onCallEnded(callId, reason);
            }

            @Override public void onMediaUpdate(String callId, String sdp) {
                VertoCallListener listener = ctx.getAttribute("callListener");
                if (listener != null) listener.onMediaUpdate(callId, sdp);
            }

            @Override public void onError(String error) {
                VertoCallListener listener = ctx.getAttribute("callListener");
                if (listener != null) listener.onError(error);
            }
        });

        ctx.setAttribute("vertoClient", client);
        client.connect();

        return result;
    }

    @Override
    public CompletableFuture<Boolean> sendHeartbeat(RouteHealthContext ctx) {
        VertoClient client = ctx.getAttribute("vertoClient");
        if (client == null) {
            return CompletableFuture.completedFuture(false);
        }
        // Verto ping/pong is handled by the client internally.
        // If WebSocket is alive, heartbeat succeeds.
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public void disconnect(RouteHealthContext ctx) {
        VertoClient client = ctx.getAttribute("vertoClient");
        if (client != null) {
            client.disconnect();
            ctx.setAttribute("vertoClient", null);
        }
    }

    @Override
    public String getProtocolName() {
        return "VERTO";
    }

    /**
     * Interface for forwarding call events from the route's VertoClient.
     */
    public interface VertoCallListener {
        void onIncomingCall(String callId, String callerNumber, String sdp);
        void onCallAnswered(String callId, String sdp);
        void onCallEnded(String callId, String reason);
        void onMediaUpdate(String callId, String sdp);
        void onError(String error);
    }
}
