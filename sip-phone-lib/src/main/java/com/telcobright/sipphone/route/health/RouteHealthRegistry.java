package com.telcobright.sipphone.route.health;

import com.telcobright.sipphone.route.RouteStatus;
import com.telcobright.sipphone.statemachine.GenericStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Manages route health state machines.
 *
 * Protocol-agnostic registry. Each route gets its own state machine that
 * tracks connectivity via a pluggable RouteConnectionHandler.
 *
 * Usage:
 *   registry.registerHandler("SIP", sipHandler);
 *   registry.registerHandler("VERTO", vertoHandler);
 *   registry.registerRoute(RouteConfig.builder("gp-trunk", "10.246.7.11:5060", "SIP").build());
 *   registry.registerRoute(RouteConfig.builder("verto-9000", "wss://10.10.194.1:8082", "VERTO")
 *       .heartbeatMode(HeartbeatMode.PASSIVE)
 *       .protocolParam("userId", "9000")
 *       .build());
 *   registry.startAll();
 *
 * Custom route readiness:
 *   The handler's connect() defines what "connected" means for that protocol.
 *   For SIP: OPTIONS 200 OK.
 *   For Verto: WebSocket connected + user registered.
 *   For HTTP: GET /health → 200.
 *   The registry doesn't care — it just calls the handler.
 */
public class RouteHealthRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(RouteHealthRegistry.class);

    private final ScheduledExecutorService scheduler;
    private final Map<String, RouteConnectionHandler> handlers = new ConcurrentHashMap<>();
    private final Map<String, ManagedRoute> routes = new ConcurrentHashMap<>();
    private volatile boolean started = false;

    public RouteHealthRegistry(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    // ==================== Handler Registration ====================

    /**
     * Register a protocol handler. Call before registering routes.
     * One handler per protocol, shared across all routes of that protocol.
     */
    public void registerHandler(String protocol, RouteConnectionHandler handler) {
        handlers.put(protocol.toUpperCase(), handler);
        LOG.info("[RouteHealthRegistry] Handler registered: {} ({})", protocol, handler.getClass().getSimpleName());
    }

    // ==================== Route Registration ====================

    /**
     * Register a route. Creates a health state machine but does NOT start it.
     * Call startAll() or startRoute() to begin health monitoring.
     */
    public void registerRoute(RouteConfig config) {
        String protocol = config.getProtocol().toUpperCase();
        RouteConnectionHandler handler = handlers.get(protocol);
        if (handler == null) {
            throw new IllegalArgumentException("No handler registered for protocol: " + protocol
                + ". Register handler first via registerHandler()");
        }

        // Build context from config
        RouteHealthContext ctx = buildContext(config);

        // Create state machine
        RouteHealthMachineFactory factory = new RouteHealthMachineFactory(scheduler, handler);
        GenericStateMachine<RouteHealthEntity, RouteHealthContext> machine = factory.createMachineTemplate();

        RouteHealthEntity entity = new RouteHealthEntity(config.getRouteId());
        machine.setId(config.getRouteId());
        machine.setPersistingEntity(entity);
        machine.setContext(ctx);

        ManagedRoute managed = new ManagedRoute(config, machine, ctx, factory);
        ManagedRoute existing = routes.putIfAbsent(config.getRouteId(), managed);
        if (existing != null) {
            throw new IllegalArgumentException("Route already registered: " + config.getRouteId());
        }

        LOG.info("[RouteHealthRegistry] Route registered: {} ({}, endpoint={})",
            config.getRouteId(), protocol, config.getEndpoint());
    }

    private RouteHealthContext buildContext(RouteConfig config) {
        RouteHealthContext ctx = new RouteHealthContext(
            config.getRouteId(), config.getRouteName(),
            config.getEndpoint(), config.getProtocol());

        ctx.setHeartbeatMode(config.getHeartbeatMode());
        ctx.setHeartbeatIntervalMs(config.getHeartbeatIntervalMs());
        ctx.setHeartbeatTimeoutMs(config.getHeartbeatTimeoutMs());
        ctx.setPassiveHeartbeatExpectedIntervalMs(config.getPassiveHeartbeatExpectedIntervalMs());
        ctx.setMaxConsecutiveHeartbeatFailures(config.getMaxConsecutiveHeartbeatFailures());
        ctx.setConnectTimeoutMs(config.getConnectTimeoutMs());
        ctx.setAutoReconnect(config.isAutoReconnect());
        ctx.setReconnectBaseDelayMs(config.getReconnectBaseDelayMs());
        ctx.setReconnectMaxDelayMs(config.getReconnectMaxDelayMs());
        ctx.setCircuitBreakerThreshold(config.getCircuitBreakerThreshold());
        ctx.setCircuitBreakerWindowMs(config.getCircuitBreakerWindowMs());

        // Copy protocol-specific params into context attributes
        for (Map.Entry<String, Object> entry : config.getProtocolConfig().entrySet()) {
            ctx.setAttribute(entry.getKey(), entry.getValue());
        }

        return ctx;
    }

    // ==================== Lifecycle ====================

    /**
     * Start all registered routes. Each transitions IDLE → CONNECTING.
     */
    public void startAll() {
        LOG.info("[RouteHealthRegistry] Starting {} route(s)...", routes.size());
        for (ManagedRoute managed : routes.values()) {
            startMachine(managed);
        }
        started = true;
        LOG.info("[RouteHealthRegistry] All {} route(s) started", routes.size());
    }

    /**
     * Start a single route by ID.
     */
    public void startRoute(String routeId) {
        ManagedRoute managed = routes.get(routeId);
        if (managed == null) {
            LOG.warn("[RouteHealthRegistry] Cannot start unknown route: {}", routeId);
            return;
        }
        startMachine(managed);
    }

    private void startMachine(ManagedRoute managed) {
        try {
            /* Listen for state transitions → notify route status changes */
            String routeId = managed.config.getRouteId();
            managed.machine.setOnStateTransition((from, to, event) -> {
                RouteStatus status = managed.ctx.getStatus();
                LOG.debug("[RouteHealthRegistry] {} {} → {} (status={})", routeId, from, to, status);
                notifyRouteStatusChanged(routeId, status);
            });

            managed.machine.start();
            managed.machine.transitionTo("CONNECTING");
            LOG.info("[RouteHealthRegistry] Route {} → CONNECTING", managed.config.getRouteId());
        } catch (Exception e) {
            LOG.error("[RouteHealthRegistry] Failed to start route: {}", managed.config.getRouteId(), e);
        }
    }

    /**
     * Stop all routes. Cancel timers, transition to IDLE.
     */
    public void stopAll() {
        LOG.info("[RouteHealthRegistry] Stopping {} route(s)...", routes.size());
        for (ManagedRoute managed : routes.values()) {
            stopMachine(managed);
        }
        started = false;
    }

    /**
     * Stop a single route.
     */
    public void stopRoute(String routeId) {
        ManagedRoute managed = routes.get(routeId);
        if (managed != null) {
            stopMachine(managed);
        }
    }

    private void stopMachine(ManagedRoute managed) {
        try {
            managed.ctx.cleanup();
            managed.entity().markComplete();
            LOG.info("[RouteHealthRegistry] Route {} stopped", managed.config.getRouteId());
        } catch (Exception e) {
            LOG.warn("[RouteHealthRegistry] Error stopping route {}: {}",
                managed.config.getRouteId(), e.getMessage());
        }
    }

    /**
     * Unregister and stop a route.
     */
    public void removeRoute(String routeId) {
        ManagedRoute managed = routes.remove(routeId);
        if (managed != null) {
            stopMachine(managed);
        }
    }

    // ==================== Status Query ====================

    /**
     * Get status of a route.
     */
    public RouteStatus getStatus(String routeId) {
        ManagedRoute managed = routes.get(routeId);
        return managed != null ? managed.ctx.getStatus() : RouteStatus.DOWN;
    }

    /**
     * Check if a route is available for traffic.
     */
    public boolean isAvailable(String routeId) {
        return getStatus(routeId).isRoutable();
    }

    /**
     * Get all route IDs with a specific status.
     */
    public List<String> getRouteIdsByStatus(RouteStatus status) {
        return routes.entrySet().stream()
            .filter(e -> e.getValue().ctx.getStatus() == status)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    /**
     * Get all available (routable) route IDs.
     */
    public List<String> getAvailableRouteIds() {
        return routes.entrySet().stream()
            .filter(e -> e.getValue().ctx.getStatus().isRoutable())
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    /**
     * Get all registered route IDs.
     */
    public Set<String> getAllRouteIds() {
        return Collections.unmodifiableSet(routes.keySet());
    }

    /**
     * Get route count.
     */
    public int getRouteCount() {
        return routes.size();
    }

    // ==================== Signaling Feedback ====================

    /**
     * Report that a signaling operation through this route succeeded.
     * Feeds the circuit breaker — may promote PARTIALLY_AVAILABLE → UP.
     */
    public void reportSignalingSuccess(String routeId) {
        ManagedRoute managed = routes.get(routeId);
        if (managed != null) {
            managed.ctx.recordSignalingSuccess();
        }
    }

    /**
     * Report that a signaling operation through this route failed.
     * Feeds the circuit breaker — may degrade UP → PARTIALLY_AVAILABLE.
     */
    public void reportSignalingFailure(String routeId) {
        ManagedRoute managed = routes.get(routeId);
        if (managed != null) {
            managed.ctx.recordSignalingFailure();
        }
    }

    /**
     * Notify the route that a remote heartbeat/ping was received (passive mode).
     * Call this when the protocol receives an unsolicited ping from the remote side.
     */
    public void notifyRemotePing(String routeId) {
        ManagedRoute managed = routes.get(routeId);
        if (managed != null) {
            managed.ctx.recordRemotePing();
        }
    }

    // ==================== Admin Actions ====================

    /**
     * Suspend a route (administrative disable).
     */
    public void suspendRoute(String routeId) {
        ManagedRoute managed = routes.get(routeId);
        if (managed != null) {
            managed.machine.fire(new RouteHealthEvent(RouteHealthEventType.SUSPEND));
        }
    }

    /**
     * Resume a suspended route.
     */
    public void resumeRoute(String routeId) {
        ManagedRoute managed = routes.get(routeId);
        if (managed != null) {
            managed.machine.fire(new RouteHealthEvent(RouteHealthEventType.RESUME));
        }
    }

    // ==================== Health / Monitoring ====================

    /**
     * Get health snapshot for one route.
     */
    public Map<String, Object> getRouteHealth(String routeId) {
        ManagedRoute managed = routes.get(routeId);
        if (managed == null) {
            return Collections.singletonMap("error", "Route not found: " + routeId);
        }
        Map<String, Object> health = managed.ctx.getHealthSnapshot();
        health.put("machineState", managed.machine.getCurrentState());
        return health;
    }

    /**
     * Get health snapshot for all routes.
     */
    public Map<String, Map<String, Object>> getAllRouteHealth() {
        Map<String, Map<String, Object>> all = new LinkedHashMap<>();
        for (Map.Entry<String, ManagedRoute> entry : routes.entrySet()) {
            Map<String, Object> health = entry.getValue().ctx.getHealthSnapshot();
            health.put("machineState", entry.getValue().machine.getCurrentState());
            all.put(entry.getKey(), health);
        }
        return all;
    }

    /**
     * Get registry summary.
     */
    public Map<String, Object> getSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRoutes", routes.size());
        summary.put("started", started);
        summary.put("handlers", handlers.keySet());

        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        for (RouteStatus s : RouteStatus.values()) {
            statusCounts.put(s.name(), 0);
        }
        for (ManagedRoute managed : routes.values()) {
            String status = managed.ctx.getStatus().name();
            statusCounts.merge(status, 1, Integer::sum);
        }
        summary.put("statusDistribution", statusCounts);

        return summary;
    }

    // ==================== Internal ====================

    /**
     * Get the config for a route (for routing logic to read protocol params).
     */
    public RouteConfig getRouteConfig(String routeId) {
        ManagedRoute managed = routes.get(routeId);
        return managed != null ? managed.config : null;
    }

    /**
     * Get the context for a route (for advanced use cases).
     */
    public RouteHealthContext getRouteContext(String routeId) {
        ManagedRoute managed = routes.get(routeId);
        return managed != null ? managed.ctx : null;
    }

    // ==================== Route Status Listener ====================

    private volatile RouteStatusListener routeStatusListener;

    @FunctionalInterface
    public interface RouteStatusListener {
        void onRouteStatusChanged(String routeId, RouteStatus status);
    }

    public void setRouteStatusListener(RouteStatusListener listener) {
        this.routeStatusListener = listener;
    }

    /** Called internally when route context status changes. */
    void notifyRouteStatusChanged(String routeId, RouteStatus status) {
        if (routeStatusListener != null) {
            try {
                routeStatusListener.onRouteStatusChanged(routeId, status);
            } catch (Exception e) {
                LOG.error("[RouteHealthRegistry] Status listener error for {}", routeId, e);
            }
        }
    }

    // ==================== Additional Route Management ====================

    /**
     * Check if a route is registered (regardless of status).
     */
    public boolean isRegistered(String routeId) {
        return routes.containsKey(routeId);
    }

    /**
     * Unregister a route. Stops the machine and removes it.
     */
    public void unregisterRoute(String routeId) {
        ManagedRoute managed = routes.remove(routeId);
        if (managed != null) {
            stopMachine(managed);
            LOG.info("[RouteHealthRegistry] Route unregistered: {}", routeId);
        }
    }

    /**
     * Internal wrapper for a registered route.
     */
    private static class ManagedRoute {
        final RouteConfig config;
        final GenericStateMachine<RouteHealthEntity, RouteHealthContext> machine;
        final RouteHealthContext ctx;
        final RouteHealthMachineFactory factory;

        ManagedRoute(RouteConfig config,
                     GenericStateMachine<RouteHealthEntity, RouteHealthContext> machine,
                     RouteHealthContext ctx,
                     RouteHealthMachineFactory factory) {
            this.config = config;
            this.machine = machine;
            this.ctx = ctx;
            this.factory = factory;
        }

        RouteHealthEntity entity() { return machine.getPersistingEntity(); }
    }
}
