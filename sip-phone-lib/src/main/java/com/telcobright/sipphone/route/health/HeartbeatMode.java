package com.telcobright.sipphone.route.health;

/**
 * How heartbeat/keepalive works for this route.
 */
public enum HeartbeatMode {
    /** We send ping, expect pong response within timeout */
    ACTIVE,
    /** Remote sends us periodic pings, we detect absence */
    PASSIVE,
    /** Both: we send AND we expect remote pings */
    BOTH,
    /** No heartbeat — rely on signaling failures for health */
    NONE
}
