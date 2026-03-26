package com.telcobright.sipphone.route.health;

public enum RouteHealthEventType {
    START,                // Begin connection attempt (IDLE/DISCONNECTED → CONNECTING)
    CONNECTED,            // Connection established (CONNECTING → CONNECTED)
    CONNECT_FAILED,       // Connection attempt failed (CONNECTING → DISCONNECTED)
    HEARTBEAT_SUCCESS,    // Heartbeat pong received or remote ping received (stay CONNECTED)
    HEARTBEAT_FAILURE,    // Heartbeat pong timeout or send failure (stay or → DISCONNECTED)
    HEARTBEAT_TIMEOUT,    // No remote heartbeat within expected interval (passive mode)
    SIGNALING_SUCCESS,    // A signaling operation succeeded (circuit breaker reset)
    SIGNALING_FAILURE,    // A signaling operation failed (circuit breaker increment)
    DISCONNECT,           // Connection lost unexpectedly (→ DISCONNECTED)
    SUSPEND,              // Administrative disable (→ SUSPENDED)
    RESUME                // Administrative re-enable (SUSPENDED → CONNECTING)
}
