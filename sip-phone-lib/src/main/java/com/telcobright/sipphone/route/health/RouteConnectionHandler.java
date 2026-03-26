package com.telcobright.sipphone.route.health;

import java.util.concurrent.CompletableFuture;

/**
 * Protocol-specific connection and heartbeat handler.
 * Implementations handle the wire-level details for each protocol.
 *
 * Examples:
 * - SIP: send OPTIONS, expect 200 OK
 * - ESL: TCP connect to FreeSWITCH port 8021, authenticate
 * - HTTP: GET /health endpoint, expect 200
 * - WebSocket: WS connect, handle ping/pong frames
 * - Sigtran: UDP probe
 */
public interface RouteConnectionHandler {

    /**
     * Attempt to establish connection to the route endpoint.
     * Returns a future that completes with true on success, false on failure.
     * The future should timeout internally if the protocol has a connect timeout.
     */
    CompletableFuture<Boolean> connect(RouteHealthContext ctx);

    /**
     * Send a heartbeat/ping to verify the connection is still alive.
     * Returns a future that completes with true if pong/response received, false otherwise.
     */
    CompletableFuture<Boolean> sendHeartbeat(RouteHealthContext ctx);

    /**
     * Gracefully disconnect from the endpoint.
     * Best-effort — called during cleanup, exceptions are logged and swallowed.
     */
    void disconnect(RouteHealthContext ctx);

    /**
     * Protocol name for logging (e.g. "SIP", "ESL", "HTTP", "SIGTRAN")
     */
    String getProtocolName();
}
