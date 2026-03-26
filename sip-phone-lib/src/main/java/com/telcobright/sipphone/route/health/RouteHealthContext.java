package com.telcobright.sipphone.route.health;

import com.telcobright.sipphone.route.RouteStatus;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Volatile per-route context for the health state machine.
 * Tracks timers, counters, timestamps, and configuration.
 * Must be cleaned up before pool return.
 */
public class RouteHealthContext {

    // ==================== Identity ====================
    private final String routeId;
    private final String routeName;
    private final String endpoint;        // e.g. "10.10.199.1:8282"
    private final String protocolName;    // e.g. "SIGTRAN", "ESL", "HTTP"

    // ==================== Configuration ====================
    private HeartbeatMode heartbeatMode = HeartbeatMode.ACTIVE;
    private long connectTimeoutMs = 10_000;
    private long heartbeatIntervalMs = 30_000;
    private long heartbeatTimeoutMs = 10_000;
    private long passiveHeartbeatExpectedIntervalMs = 60_000;  // max gap between remote pings
    private int maxConsecutiveHeartbeatFailures = 3;
    private boolean autoReconnect = true;
    private long reconnectBaseDelayMs = 5_000;
    private long reconnectMaxDelayMs = 300_000;  // 5 minutes max backoff
    // Circuit breaker
    private int circuitBreakerThreshold = 10;         // failures within window
    private long circuitBreakerWindowMs = 60_000;     // 1 minute window

    // ==================== Runtime State ====================
    private volatile RouteStatus status = RouteStatus.DOWN;
    private volatile Instant connectedSince;
    private volatile Instant disconnectedSince;
    private volatile Instant lastHeartbeatSentTime;
    private volatile Instant lastHeartbeatReceivedTime;
    private volatile Instant lastRemotePingTime;       // passive mode: last ping from remote
    private volatile long lastHeartbeatLatencyMs;

    // ==================== Counters ====================
    private final AtomicInteger connectAttempts = new AtomicInteger(0);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicInteger consecutiveHeartbeatFailures = new AtomicInteger(0);
    private final AtomicLong totalHeartbeatSuccess = new AtomicLong(0);
    private final AtomicLong totalHeartbeatFailures = new AtomicLong(0);
    private final AtomicInteger signalingFailuresInWindow = new AtomicInteger(0);
    private volatile long signalingWindowStart = System.currentTimeMillis();

    // ==================== Timers ====================
    private final Map<String, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();

    // ==================== Protocol-specific data ====================
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    public RouteHealthContext(String routeId, String routeName, String endpoint, String protocolName) {
        this.routeId = routeId;
        this.routeName = routeName;
        this.endpoint = endpoint;
        this.protocolName = protocolName;
    }

    // ==================== Status ====================

    public RouteStatus getStatus() { return status; }

    public void setStatus(RouteStatus status) { this.status = status; }

    public boolean isUp() { return status == RouteStatus.UP; }

    // ==================== Timer Management ====================

    public void storeTimer(String name, ScheduledFuture<?> future) {
        ScheduledFuture<?> prev = timers.put(name, future);
        if (prev != null && !prev.isDone()) {
            prev.cancel(false);
        }
    }

    public void cancelTimer(String name) {
        ScheduledFuture<?> future = timers.remove(name);
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
    }

    public void cancelAllTimers() {
        for (ScheduledFuture<?> future : timers.values()) {
            if (future != null && !future.isDone()) {
                future.cancel(false);
            }
        }
        timers.clear();
    }

    // ==================== Heartbeat Tracking ====================

    public void recordHeartbeatSuccess(long latencyMs) {
        consecutiveHeartbeatFailures.set(0);
        totalHeartbeatSuccess.incrementAndGet();
        lastHeartbeatReceivedTime = Instant.now();
        lastHeartbeatLatencyMs = latencyMs;
    }

    public int recordHeartbeatFailure() {
        totalHeartbeatFailures.incrementAndGet();
        return consecutiveHeartbeatFailures.incrementAndGet();
    }

    public void recordRemotePing() {
        lastRemotePingTime = Instant.now();
    }

    public boolean isRemotePingOverdue() {
        if (lastRemotePingTime == null) return false;
        long elapsed = System.currentTimeMillis() - lastRemotePingTime.toEpochMilli();
        return elapsed > passiveHeartbeatExpectedIntervalMs;
    }

    // ==================== Circuit Breaker ====================

    public void recordSignalingFailure() {
        long now = System.currentTimeMillis();
        // Reset window if expired
        if (now - signalingWindowStart > circuitBreakerWindowMs) {
            signalingFailuresInWindow.set(0);
            signalingWindowStart = now;
        }
        signalingFailuresInWindow.incrementAndGet();
    }

    public void recordSignalingSuccess() {
        signalingFailuresInWindow.decrementAndGet();
        if (signalingFailuresInWindow.get() < 0) {
            signalingFailuresInWindow.set(0);
        }
    }

    public boolean isCircuitBreakerTripped() {
        long now = System.currentTimeMillis();
        if (now - signalingWindowStart > circuitBreakerWindowMs) {
            signalingFailuresInWindow.set(0);
            signalingWindowStart = now;
            return false;
        }
        return signalingFailuresInWindow.get() >= circuitBreakerThreshold;
    }

    // ==================== Reconnect Backoff ====================

    public long getReconnectBackoffMs() {
        int attempts = reconnectAttempts.get();
        long delay = reconnectBaseDelayMs * (1L << Math.min(attempts, 10)); // cap exponent at 10
        return Math.min(delay, reconnectMaxDelayMs);
    }

    // ==================== Cleanup ====================

    /**
     * Cancel all timers and reset counters. Call before pool return.
     */
    public void cleanup() {
        cancelAllTimers();
        status = RouteStatus.DOWN;
        connectedSince = null;
        disconnectedSince = null;
        lastHeartbeatSentTime = null;
        lastHeartbeatReceivedTime = null;
        lastRemotePingTime = null;
        lastHeartbeatLatencyMs = 0;
        connectAttempts.set(0);
        reconnectAttempts.set(0);
        consecutiveHeartbeatFailures.set(0);
        totalHeartbeatSuccess.set(0);
        totalHeartbeatFailures.set(0);
        signalingFailuresInWindow.set(0);
        attributes.clear();
    }

    // ==================== Health Snapshot ====================

    public Map<String, Object> getHealthSnapshot() {
        Map<String, Object> snap = new java.util.LinkedHashMap<>();
        snap.put("routeId", routeId);
        snap.put("routeName", routeName);
        snap.put("endpoint", endpoint);
        snap.put("protocol", protocolName);
        snap.put("status", status.name());
        snap.put("connectedSince", connectedSince);
        snap.put("disconnectedSince", disconnectedSince);
        snap.put("connectAttempts", connectAttempts.get());
        snap.put("reconnectAttempts", reconnectAttempts.get());
        snap.put("consecutiveHbFailures", consecutiveHeartbeatFailures.get());
        snap.put("totalHbSuccess", totalHeartbeatSuccess.get());
        snap.put("totalHbFailures", totalHeartbeatFailures.get());
        snap.put("lastHbLatencyMs", lastHeartbeatLatencyMs);
        snap.put("lastHbReceived", lastHeartbeatReceivedTime);
        snap.put("lastRemotePing", lastRemotePingTime);
        snap.put("signalingFailuresInWindow", signalingFailuresInWindow.get());
        snap.put("circuitBreakerTripped", isCircuitBreakerTripped());
        snap.put("activeTimers", timers.size());
        if (connectedSince != null) {
            snap.put("uptimeMs", System.currentTimeMillis() - connectedSince.toEpochMilli());
        }
        return snap;
    }

    // ==================== Getters / Setters ====================

    public String getRouteId() { return routeId; }
    public String getRouteName() { return routeName; }
    public String getEndpoint() { return endpoint; }
    public String getProtocolName() { return protocolName; }

    public HeartbeatMode getHeartbeatMode() { return heartbeatMode; }
    public void setHeartbeatMode(HeartbeatMode heartbeatMode) { this.heartbeatMode = heartbeatMode; }

    public long getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(long connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }

    public long getHeartbeatIntervalMs() { return heartbeatIntervalMs; }
    public void setHeartbeatIntervalMs(long heartbeatIntervalMs) { this.heartbeatIntervalMs = heartbeatIntervalMs; }

    public long getHeartbeatTimeoutMs() { return heartbeatTimeoutMs; }
    public void setHeartbeatTimeoutMs(long heartbeatTimeoutMs) { this.heartbeatTimeoutMs = heartbeatTimeoutMs; }

    public long getPassiveHeartbeatExpectedIntervalMs() { return passiveHeartbeatExpectedIntervalMs; }
    public void setPassiveHeartbeatExpectedIntervalMs(long ms) { this.passiveHeartbeatExpectedIntervalMs = ms; }

    public int getMaxConsecutiveHeartbeatFailures() { return maxConsecutiveHeartbeatFailures; }
    public void setMaxConsecutiveHeartbeatFailures(int max) { this.maxConsecutiveHeartbeatFailures = max; }

    public boolean isAutoReconnect() { return autoReconnect; }
    public void setAutoReconnect(boolean autoReconnect) { this.autoReconnect = autoReconnect; }

    public long getReconnectBaseDelayMs() { return reconnectBaseDelayMs; }
    public void setReconnectBaseDelayMs(long ms) { this.reconnectBaseDelayMs = ms; }

    public long getReconnectMaxDelayMs() { return reconnectMaxDelayMs; }
    public void setReconnectMaxDelayMs(long ms) { this.reconnectMaxDelayMs = ms; }

    public Instant getConnectedSince() { return connectedSince; }
    public void setConnectedSince(Instant connectedSince) { this.connectedSince = connectedSince; }

    public Instant getDisconnectedSince() { return disconnectedSince; }
    public void setDisconnectedSince(Instant disconnectedSince) { this.disconnectedSince = disconnectedSince; }

    public void setLastHeartbeatSentTime(Instant t) { this.lastHeartbeatSentTime = t; }
    public Instant getLastHeartbeatSentTime() { return lastHeartbeatSentTime; }

    public AtomicInteger getConnectAttempts() { return connectAttempts; }
    public AtomicInteger getReconnectAttempts() { return reconnectAttempts; }
    public AtomicInteger getConsecutiveHeartbeatFailures() { return consecutiveHeartbeatFailures; }

    public int getCircuitBreakerThreshold() { return circuitBreakerThreshold; }
    public void setCircuitBreakerThreshold(int threshold) { this.circuitBreakerThreshold = threshold; }

    public long getCircuitBreakerWindowMs() { return circuitBreakerWindowMs; }
    public void setCircuitBreakerWindowMs(long ms) { this.circuitBreakerWindowMs = ms; }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) { return (T) attributes.get(key); }
    public void setAttribute(String key, Object value) { attributes.put(key, value); }
}
