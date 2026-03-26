package com.telcobright.sipphone.route.health;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable configuration for a route endpoint.
 * Protocol-specific settings go in protocolConfig map.
 *
 * Examples:
 *   SIP trunk:   protocol="SIP",   endpoint="10.246.7.11:5060"
 *   ESL:         protocol="ESL",   endpoint="10.10.194.1:8021"
 *   Sigtran:     protocol="SIGTRAN", endpoint="10.10.199.1:8282"
 *   HTTP SMS:    protocol="HTTP",  endpoint="https://api.mno.com/send"
 *   Verto:       protocol="VERTO", endpoint="wss://10.10.194.1:8082"
 *                protocolConfig: {userId=9000, password=xxx, domain=192.168.24.101}
 */
public class RouteConfig {

    private final String routeId;
    private final String routeName;
    private final String endpoint;
    private final String protocol;

    // Heartbeat
    private final HeartbeatMode heartbeatMode;
    private final long heartbeatIntervalMs;
    private final long heartbeatTimeoutMs;
    private final long passiveHeartbeatExpectedIntervalMs;
    private final int maxConsecutiveHeartbeatFailures;

    // Connect
    private final long connectTimeoutMs;

    // Reconnect
    private final boolean autoReconnect;
    private final long reconnectBaseDelayMs;
    private final long reconnectMaxDelayMs;

    // Circuit breaker
    private final int circuitBreakerThreshold;
    private final long circuitBreakerWindowMs;

    // Protocol-specific config (opaque to the library, handler reads what it needs)
    private final Map<String, Object> protocolConfig;

    private RouteConfig(Builder builder) {
        this.routeId = builder.routeId;
        this.routeName = builder.routeName;
        this.endpoint = builder.endpoint;
        this.protocol = builder.protocol;
        this.heartbeatMode = builder.heartbeatMode;
        this.heartbeatIntervalMs = builder.heartbeatIntervalMs;
        this.heartbeatTimeoutMs = builder.heartbeatTimeoutMs;
        this.passiveHeartbeatExpectedIntervalMs = builder.passiveHeartbeatExpectedIntervalMs;
        this.maxConsecutiveHeartbeatFailures = builder.maxConsecutiveHeartbeatFailures;
        this.connectTimeoutMs = builder.connectTimeoutMs;
        this.autoReconnect = builder.autoReconnect;
        this.reconnectBaseDelayMs = builder.reconnectBaseDelayMs;
        this.reconnectMaxDelayMs = builder.reconnectMaxDelayMs;
        this.circuitBreakerThreshold = builder.circuitBreakerThreshold;
        this.circuitBreakerWindowMs = builder.circuitBreakerWindowMs;
        this.protocolConfig = Collections.unmodifiableMap(new HashMap<>(builder.protocolConfig));
    }

    // Getters
    public String getRouteId() { return routeId; }
    public String getRouteName() { return routeName; }
    public String getEndpoint() { return endpoint; }
    public String getProtocol() { return protocol; }
    public HeartbeatMode getHeartbeatMode() { return heartbeatMode; }
    public long getHeartbeatIntervalMs() { return heartbeatIntervalMs; }
    public long getHeartbeatTimeoutMs() { return heartbeatTimeoutMs; }
    public long getPassiveHeartbeatExpectedIntervalMs() { return passiveHeartbeatExpectedIntervalMs; }
    public int getMaxConsecutiveHeartbeatFailures() { return maxConsecutiveHeartbeatFailures; }
    public long getConnectTimeoutMs() { return connectTimeoutMs; }
    public boolean isAutoReconnect() { return autoReconnect; }
    public long getReconnectBaseDelayMs() { return reconnectBaseDelayMs; }
    public long getReconnectMaxDelayMs() { return reconnectMaxDelayMs; }
    public int getCircuitBreakerThreshold() { return circuitBreakerThreshold; }
    public long getCircuitBreakerWindowMs() { return circuitBreakerWindowMs; }
    public Map<String, Object> getProtocolConfig() { return protocolConfig; }

    @SuppressWarnings("unchecked")
    public <T> T getProtocolParam(String key) {
        return (T) protocolConfig.get(key);
    }

    public <T> T getProtocolParam(String key, T defaultValue) {
        @SuppressWarnings("unchecked")
        T value = (T) protocolConfig.get(key);
        return value != null ? value : defaultValue;
    }

    public static Builder builder(String routeId, String endpoint, String protocol) {
        return new Builder(routeId, endpoint, protocol);
    }

    public static class Builder {
        private final String routeId;
        private String routeName;
        private final String endpoint;
        private final String protocol;
        private HeartbeatMode heartbeatMode = HeartbeatMode.ACTIVE;
        private long heartbeatIntervalMs = 30_000;
        private long heartbeatTimeoutMs = 10_000;
        private long passiveHeartbeatExpectedIntervalMs = 60_000;
        private int maxConsecutiveHeartbeatFailures = 3;
        private long connectTimeoutMs = 10_000;
        private boolean autoReconnect = true;
        private long reconnectBaseDelayMs = 5_000;
        private long reconnectMaxDelayMs = 300_000;
        private int circuitBreakerThreshold = 10;
        private long circuitBreakerWindowMs = 60_000;
        private final Map<String, Object> protocolConfig = new HashMap<>();

        private Builder(String routeId, String endpoint, String protocol) {
            this.routeId = routeId;
            this.routeName = routeId;
            this.endpoint = endpoint;
            this.protocol = protocol;
        }

        public Builder routeName(String routeName) { this.routeName = routeName; return this; }
        public Builder heartbeatMode(HeartbeatMode mode) { this.heartbeatMode = mode; return this; }
        public Builder heartbeatIntervalMs(long ms) { this.heartbeatIntervalMs = ms; return this; }
        public Builder heartbeatTimeoutMs(long ms) { this.heartbeatTimeoutMs = ms; return this; }
        public Builder passiveHeartbeatExpectedIntervalMs(long ms) { this.passiveHeartbeatExpectedIntervalMs = ms; return this; }
        public Builder maxConsecutiveHeartbeatFailures(int n) { this.maxConsecutiveHeartbeatFailures = n; return this; }
        public Builder connectTimeoutMs(long ms) { this.connectTimeoutMs = ms; return this; }
        public Builder autoReconnect(boolean auto) { this.autoReconnect = auto; return this; }
        public Builder reconnectBaseDelayMs(long ms) { this.reconnectBaseDelayMs = ms; return this; }
        public Builder reconnectMaxDelayMs(long ms) { this.reconnectMaxDelayMs = ms; return this; }
        public Builder circuitBreakerThreshold(int threshold) { this.circuitBreakerThreshold = threshold; return this; }
        public Builder circuitBreakerWindowMs(long ms) { this.circuitBreakerWindowMs = ms; return this; }
        public Builder protocolParam(String key, Object value) { this.protocolConfig.put(key, value); return this; }
        public Builder protocolConfig(Map<String, Object> config) { this.protocolConfig.putAll(config); return this; }

        public RouteConfig build() {
            return new RouteConfig(this);
        }
    }
}
