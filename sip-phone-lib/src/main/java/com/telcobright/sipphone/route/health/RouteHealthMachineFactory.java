package com.telcobright.sipphone.route.health;

import com.telcobright.sipphone.route.RouteStatus;
import com.telcobright.sipphone.statemachine.FluentBuilder;
import com.telcobright.sipphone.statemachine.GenericStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Creates route health state machines.
 *
 * States: IDLE → CONNECTING → CONNECTED ↔ DISCONNECTED → SUSPENDED
 *
 * Each machine tracks the health of one route endpoint. The registry
 * manages multiple machines (one per endpoint) and exposes status
 * via StatusBasedRoute / WeightedStatusBasedRoute for routing decisions.
 *
 * Best practices ported from SigTranSmsRegistry/SmsMachineFactory:
 * - IDLE state with no entry action (pool safe)
 * - All entry actions wrapped in try-catch → DISCONNECTED on error
 * - All ScheduledFutures tracked in context, cancelled on cleanup
 * - isComplete/shuttingDown guards on all async callbacks
 * - context.cleanup() on terminal state before pool return
 * - Exponential backoff on reconnect
 * - Circuit breaker for signaling failure rate
 */
public class RouteHealthMachineFactory {

    private static final Logger LOG = LoggerFactory.getLogger(RouteHealthMachineFactory.class);

    private static final String TIMER_CONNECT_TIMEOUT = "connectTimeout";
    private static final String TIMER_HEARTBEAT = "heartbeat";
    private static final String TIMER_PASSIVE_WATCHDOG = "passiveWatchdog";
    private static final String TIMER_RECONNECT = "reconnect";

    private final ScheduledExecutorService scheduler;
    private final RouteConnectionHandler handler;

    public RouteHealthMachineFactory(ScheduledExecutorService scheduler, RouteConnectionHandler handler) {
        this.scheduler = scheduler;
        this.handler = handler;
    }

    /**
     * Persistent entity placeholder — route health uses context only,
     * no persisting entity needed. We use a simple String holder for the route ID.
     */
    public GenericStateMachine<RouteHealthEntity, RouteHealthContext> createMachineTemplate() {
        return FluentBuilder.<RouteHealthEntity, RouteHealthContext>createForPooling()
            .initialState("IDLE")

            // ==================== IDLE ====================
            // Pool-safe. No entry action. Waits for START event.
            .state("IDLE")
            .done()

            // ==================== CONNECTING ====================
            .state("CONNECTING")
                .onEntry(machine -> {
                  try {
                    RouteHealthContext ctx = machine.getContext();
                    String routeId = machine.getId();

                    ctx.setStatus(RouteStatus.DOWN);  // Not yet connected
                    ctx.getConnectAttempts().incrementAndGet();

                    LOG.info("[RouteHealth-{}] CONNECTING (attempt #{}, endpoint={}, protocol={})",
                        routeId, ctx.getConnectAttempts().get(), ctx.getEndpoint(), ctx.getProtocolName());

                    // Schedule connect timeout
                    ScheduledFuture<?> timeoutFuture = scheduler.schedule(() -> {
                        if (!"CONNECTING".equals(machine.getCurrentState())) return;
                        LOG.warn("[RouteHealth-{}] Connect timeout ({}ms)", routeId, ctx.getConnectTimeoutMs());
                        machine.fire(new RouteHealthEvent(RouteHealthEventType.CONNECT_FAILED, "Connect timeout"));
                    }, ctx.getConnectTimeoutMs(), TimeUnit.MILLISECONDS);
                    ctx.storeTimer(TIMER_CONNECT_TIMEOUT, timeoutFuture);

                    // Call protocol-specific connect
                    CompletableFuture<Boolean> connectFuture = handler.connect(ctx);
                    connectFuture.whenComplete((success, error) -> {
                        if (!"CONNECTING".equals(machine.getCurrentState())) return;
                        ctx.cancelTimer(TIMER_CONNECT_TIMEOUT);

                        if (error != null) {
                            LOG.warn("[RouteHealth-{}] Connect error: {}", routeId, error.getMessage());
                            machine.fire(new RouteHealthEvent(RouteHealthEventType.CONNECT_FAILED, error.getMessage()));
                        } else if (Boolean.TRUE.equals(success)) {
                            machine.fire(new RouteHealthEvent(RouteHealthEventType.CONNECTED));
                        } else {
                            machine.fire(new RouteHealthEvent(RouteHealthEventType.CONNECT_FAILED, "Connect returned false"));
                        }
                    });

                  } catch (Throwable e) {
                    LOG.error("[RouteHealth-{}] Exception in CONNECTING entry", machine.getId(), e);
                    machine.fire(new RouteHealthEvent(RouteHealthEventType.CONNECT_FAILED, e.getMessage()));
                  }
                })
            .done()

            // ==================== CONNECTED ====================
            .state("CONNECTED")
                .onEntry(machine -> {
                  try {
                    RouteHealthContext ctx = machine.getContext();
                    String routeId = machine.getId();

                    ctx.setStatus(RouteStatus.UP);
                    ctx.setConnectedSince(Instant.now());
                    ctx.setDisconnectedSince(null);
                    ctx.getConsecutiveHeartbeatFailures().set(0);
                    ctx.getReconnectAttempts().set(0);  // Reset backoff on successful connect

                    LOG.info("[RouteHealth-{}] CONNECTED (status=UP, endpoint={})", routeId, ctx.getEndpoint());

                    // Start heartbeat based on mode
                    HeartbeatMode mode = ctx.getHeartbeatMode();
                    if (mode == HeartbeatMode.ACTIVE || mode == HeartbeatMode.BOTH) {
                        scheduleActiveHeartbeat(machine);
                    }
                    if (mode == HeartbeatMode.PASSIVE || mode == HeartbeatMode.BOTH) {
                        schedulePassiveWatchdog(machine);
                    }

                  } catch (Throwable e) {
                    LOG.error("[RouteHealth-{}] Exception in CONNECTED entry", machine.getId(), e);
                    machine.fire(new RouteHealthEvent(RouteHealthEventType.DISCONNECT, e.getMessage()));
                  }
                })
                .stay(RouteHealthEvent.class, (machine, rawEvent) -> {
                    RouteHealthEvent event = (RouteHealthEvent) rawEvent;
                    RouteHealthContext ctx = machine.getContext();
                    String routeId = machine.getId();

                    switch (event.getType()) {
                        case HEARTBEAT_SUCCESS:
                            ctx.recordHeartbeatSuccess(0);  // latency from payload if available
                            LOG.debug("[RouteHealth-{}] Heartbeat OK", routeId);
                            // If was degraded, check if circuit breaker cleared
                            if (ctx.getStatus() == RouteStatus.PARTIALLY_AVAILABLE && !ctx.isCircuitBreakerTripped()) {
                                ctx.setStatus(RouteStatus.UP);
                                LOG.info("[RouteHealth-{}] Circuit breaker cleared → status=UP", routeId);
                            }
                            // Reschedule next heartbeat
                            if (ctx.getHeartbeatMode() == HeartbeatMode.ACTIVE || ctx.getHeartbeatMode() == HeartbeatMode.BOTH) {
                                scheduleActiveHeartbeat(machine);
                            }
                            break;

                        case HEARTBEAT_FAILURE:
                        case HEARTBEAT_TIMEOUT:
                            int failures = ctx.recordHeartbeatFailure();
                            LOG.warn("[RouteHealth-{}] Heartbeat failure #{}/{}: {}",
                                routeId, failures, ctx.getMaxConsecutiveHeartbeatFailures(), event.getMessage());
                            if (failures >= ctx.getMaxConsecutiveHeartbeatFailures()) {
                                LOG.error("[RouteHealth-{}] Max heartbeat failures reached → DISCONNECTED", routeId);
                                machine.transitionTo("DISCONNECTED");
                            } else {
                                // Retry heartbeat sooner
                                if (ctx.getHeartbeatMode() == HeartbeatMode.ACTIVE || ctx.getHeartbeatMode() == HeartbeatMode.BOTH) {
                                    scheduleActiveHeartbeat(machine);
                                }
                            }
                            break;

                        case SIGNALING_FAILURE:
                            ctx.recordSignalingFailure();
                            if (ctx.isCircuitBreakerTripped() && ctx.getStatus() == RouteStatus.UP) {
                                ctx.setStatus(RouteStatus.PARTIALLY_AVAILABLE);
                                LOG.warn("[RouteHealth-{}] Circuit breaker tripped → status=PARTIALLY_AVAILABLE", routeId);
                            }
                            break;

                        case SIGNALING_SUCCESS:
                            ctx.recordSignalingSuccess();
                            if (ctx.getStatus() == RouteStatus.PARTIALLY_AVAILABLE && !ctx.isCircuitBreakerTripped()) {
                                ctx.setStatus(RouteStatus.UP);
                                LOG.info("[RouteHealth-{}] Circuit breaker cleared → status=UP", routeId);
                            }
                            break;

                        case DISCONNECT:
                            LOG.warn("[RouteHealth-{}] Disconnect event: {}", routeId, event.getMessage());
                            machine.transitionTo("DISCONNECTED");
                            break;

                        case SUSPEND:
                            machine.transitionTo("SUSPENDED");
                            break;

                        default:
                            break;
                    }
                })
            .done()

            // ==================== DISCONNECTED ====================
            .state("DISCONNECTED")
                .onEntry(machine -> {
                  try {
                    RouteHealthContext ctx = machine.getContext();
                    String routeId = machine.getId();

                    ctx.setStatus(RouteStatus.DOWN);
                    ctx.setDisconnectedSince(Instant.now());
                    ctx.cancelTimer(TIMER_HEARTBEAT);
                    ctx.cancelTimer(TIMER_PASSIVE_WATCHDOG);

                    // Graceful disconnect (best-effort)
                    try {
                        handler.disconnect(ctx);
                    } catch (Exception e) {
                        LOG.debug("[RouteHealth-{}] Disconnect handler error (ignored): {}", routeId, e.getMessage());
                    }

                    LOG.warn("[RouteHealth-{}] DISCONNECTED (status=DOWN, endpoint={})", routeId, ctx.getEndpoint());

                    // Auto-reconnect with exponential backoff
                    if (ctx.isAutoReconnect()) {
                        long backoffMs = ctx.getReconnectBackoffMs();
                        ctx.getReconnectAttempts().incrementAndGet();

                        LOG.info("[RouteHealth-{}] Auto-reconnect in {}ms (attempt #{})",
                            routeId, backoffMs, ctx.getReconnectAttempts().get());

                        ScheduledFuture<?> reconnectFuture = scheduler.schedule(() -> {
                            if (!"DISCONNECTED".equals(machine.getCurrentState())) return;
                            machine.transitionTo("CONNECTING");
                        }, backoffMs, TimeUnit.MILLISECONDS);
                        ctx.storeTimer(TIMER_RECONNECT, reconnectFuture);
                    }

                  } catch (Throwable e) {
                    LOG.error("[RouteHealth-{}] Exception in DISCONNECTED entry", machine.getId(), e);
                    // Already in DISCONNECTED — just log, don't transition
                  }
                })
                .stay(RouteHealthEvent.class, (machine, rawEvent) -> {
                    RouteHealthEvent event = (RouteHealthEvent) rawEvent;
                    switch (event.getType()) {
                        case START:
                            machine.getContext().cancelTimer(TIMER_RECONNECT);
                            machine.transitionTo("CONNECTING");
                            break;
                        case SUSPEND:
                            machine.getContext().cancelTimer(TIMER_RECONNECT);
                            machine.transitionTo("SUSPENDED");
                            break;
                        default:
                            break;
                    }
                })
            .done()

            // ==================== SUSPENDED ====================
            .state("SUSPENDED")
                .onEntry(machine -> {
                    RouteHealthContext ctx = machine.getContext();
                    ctx.setStatus(RouteStatus.SUSPENDED);
                    ctx.cancelAllTimers();

                    try {
                        handler.disconnect(ctx);
                    } catch (Exception e) {
                        LOG.debug("[RouteHealth-{}] Disconnect on suspend (ignored): {}", machine.getId(), e.getMessage());
                    }

                    LOG.info("[RouteHealth-{}] SUSPENDED (status=SUSPENDED)", machine.getId());
                })
                .stay(RouteHealthEvent.class, (machine, rawEvent) -> {
                    RouteHealthEvent event = (RouteHealthEvent) rawEvent;
                    if (event.getType() == RouteHealthEventType.RESUME) {
                        LOG.info("[RouteHealth-{}] RESUME → CONNECTING", machine.getId());
                        machine.transitionTo("CONNECTING");
                    }
                })
            .done()

            .build();
    }

    // ==================== Heartbeat Scheduling ====================

    /**
     * Schedule active heartbeat: we send ping, expect response.
     */
    private void scheduleActiveHeartbeat(GenericStateMachine<RouteHealthEntity, RouteHealthContext> machine) {
        RouteHealthContext ctx = machine.getContext();
        String routeId = machine.getId();

        ScheduledFuture<?> hbFuture = scheduler.schedule(() -> {
            if (!"CONNECTED".equals(machine.getCurrentState())) return;

            ctx.setLastHeartbeatSentTime(Instant.now());
            long sentAt = System.currentTimeMillis();

            try {
                CompletableFuture<Boolean> hbResult = handler.sendHeartbeat(ctx);
                hbResult
                    .orTimeout(ctx.getHeartbeatTimeoutMs(), TimeUnit.MILLISECONDS)
                    .whenComplete((success, error) -> {
                        if (!"CONNECTED".equals(machine.getCurrentState())) return;

                        if (error != null) {
                            machine.fire(new RouteHealthEvent(RouteHealthEventType.HEARTBEAT_FAILURE, error.getMessage()));
                        } else if (Boolean.TRUE.equals(success)) {
                            long latency = System.currentTimeMillis() - sentAt;
                            ctx.recordHeartbeatSuccess(latency);
                            machine.fire(new RouteHealthEvent(RouteHealthEventType.HEARTBEAT_SUCCESS));
                        } else {
                            machine.fire(new RouteHealthEvent(RouteHealthEventType.HEARTBEAT_FAILURE, "Heartbeat returned false"));
                        }
                    });
            } catch (Exception e) {
                LOG.warn("[RouteHealth-{}] Heartbeat send error: {}", routeId, e.getMessage());
                machine.fire(new RouteHealthEvent(RouteHealthEventType.HEARTBEAT_FAILURE, e.getMessage()));
            }
        }, ctx.getHeartbeatIntervalMs(), TimeUnit.MILLISECONDS);

        ctx.storeTimer(TIMER_HEARTBEAT, hbFuture);
    }

    /**
     * Schedule passive heartbeat watchdog: check if remote has sent us a ping recently.
     */
    private void schedulePassiveWatchdog(GenericStateMachine<RouteHealthEntity, RouteHealthContext> machine) {
        RouteHealthContext ctx = machine.getContext();
        String routeId = machine.getId();

        // Initialize last remote ping time so first check doesn't fail
        ctx.recordRemotePing();

        ScheduledFuture<?> watchdogFuture = scheduler.scheduleAtFixedRate(() -> {
            if (!"CONNECTED".equals(machine.getCurrentState())) return;

            if (ctx.isRemotePingOverdue()) {
                LOG.warn("[RouteHealth-{}] No remote ping within {}ms", routeId, ctx.getPassiveHeartbeatExpectedIntervalMs());
                machine.fire(new RouteHealthEvent(RouteHealthEventType.HEARTBEAT_TIMEOUT, "Remote ping overdue"));
            }
        }, ctx.getPassiveHeartbeatExpectedIntervalMs(), ctx.getPassiveHeartbeatExpectedIntervalMs(), TimeUnit.MILLISECONDS);

        ctx.storeTimer(TIMER_PASSIVE_WATCHDOG, watchdogFuture);
    }
}
