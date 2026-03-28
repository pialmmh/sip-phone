package com.telcobright.sipphone.statemachine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Lightweight state machine — portable Java version of State-Walk's GenericStateMachine.
 * <p>
 * No Android dependencies. Uses ScheduledExecutorService for timeouts and SLF4J for logging.
 *
 * @param <TPersistingEntity> Persistent context (survives across states)
 * @param <TContext>          Volatile context (runtime-only data)
 */
public class GenericStateMachine<TPersistingEntity, TContext> {

    private static final Logger log = LoggerFactory.getLogger(GenericStateMachine.class);

    private String id;
    private String currentState = "";
    private String initialStateName = "";
    private boolean started;
    private boolean completed;

    private TPersistingEntity persistingEntity;
    private TContext context;

    private final ConcurrentHashMap<String, StateConfig> stateConfigs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<Class<? extends StateMachineEvent>, String>> transitions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<Class<? extends StateMachineEvent>,
            BiConsumer<GenericStateMachine<TPersistingEntity, TContext>, StateMachineEvent>>> stayActions = new ConcurrentHashMap<>();

    private Consumer<String> onTerminated;
    private TriConsumer<String, String, StateMachineEvent> onStateTransition;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sm-timeout");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> timeoutFuture;

    public GenericStateMachine() {
        this("");
    }

    public GenericStateMachine(String id) {
        this.id = id;
    }

    /* === Configuration (called by builder) === */

    void setInitialState(String state) {
        this.initialStateName = state;
        this.currentState = state;
    }

    void addStateConfig(StateConfig config) {
        stateConfigs.put(config.getStateId(), config);
    }

    void addTransition(String fromState, Class<? extends StateMachineEvent> eventClass, String toState) {
        transitions.computeIfAbsent(fromState, k -> new ConcurrentHashMap<>()).put(eventClass, toState);
    }

    void addStayAction(String stateId, Class<? extends StateMachineEvent> eventClass,
                       BiConsumer<GenericStateMachine<TPersistingEntity, TContext>, StateMachineEvent> action) {
        stayActions.computeIfAbsent(stateId, k -> new ConcurrentHashMap<>()).put(eventClass, action);
    }

    /* === Public API === */

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCurrentState() { return currentState; }

    public TPersistingEntity getPersistingEntity() { return persistingEntity; }
    public void setPersistingEntity(TPersistingEntity entity) { this.persistingEntity = entity; }

    public TContext getContext() { return context; }
    public void setContext(TContext context) { this.context = context; }

    public void setOnTerminated(Consumer<String> callback) { this.onTerminated = callback; }
    public void setOnStateTransition(TriConsumer<String, String, StateMachineEvent> callback) {
        this.onStateTransition = callback;
    }

    public boolean isInState(String state) { return currentState.equals(state); }
    public boolean isComplete() { return completed; }
    public boolean isActive() { return started && !completed; }

    /**
     * Start the machine — executes entry action of initial state.
     */
    public void start() {
        if (started) return;
        started = true;
        currentState = initialStateName;
        log.debug("[{}] Started in state: {}", id, currentState);
        executeEntryAction(currentState);
        scheduleTimeout(currentState);
    }

    /**
     * Fire an event — triggers transition if matching rule exists.
     */
    public synchronized void fire(StateMachineEvent event) {
        if (completed) {
            log.warn("[{}] Event {} ignored — machine completed", id, event.getEventType());
            return;
        }

        var stateTransitions = transitions.get(currentState);
        if (stateTransitions != null) {
            for (var entry : stateTransitions.entrySet()) {
                if (entry.getKey().isInstance(event)) {
                    performTransition(entry.getValue(), event);
                    return;
                }
            }
        }

        /* Check stay actions */
        var stateStays = stayActions.get(currentState);
        if (stateStays != null) {
            for (var entry : stateStays.entrySet()) {
                if (entry.getKey().isInstance(event)) {
                    log.debug("[{}] Stay action in {} for {}", id, currentState, event.getEventType());
                    entry.getValue().accept(this, event);
                    return;
                }
            }
            log.debug("[{}] No stay match in {} for {} (event class={}, registered={})",
                    id, currentState, event.getEventType(), event.getClass().getName(),
                    stateStays.keySet().stream().map(Class::getName).toList());
        } else {
            log.debug("[{}] No stay actions registered for state {}", id, currentState);
        }

        log.debug("[{}] Event {} ignored in state {}", id, event.getEventType(), currentState);
    }

    /**
     * Programmatic transition (from within entry/exit actions).
     */
    public synchronized void transitionTo(String newState) {
        if (completed) return;
        performTransition(newState, null);
    }

    /**
     * Reset for object pool reuse.
     */
    public void resetForReuse() {
        cancelTimeout();
        currentState = initialStateName;
        persistingEntity = null;
        context = null;
        started = false;
        completed = false;
        id = "";
    }

    /**
     * Shutdown the internal scheduler.
     */
    public void shutdown() {
        cancelTimeout();
        scheduler.shutdownNow();
    }

    /* === Internal === */

    private void performTransition(String toState, StateMachineEvent event) {
        String fromState = currentState;

        log.debug("[{}] {} -> {}{}", id, fromState, toState,
                event != null ? " (" + event.getEventType() + ")" : "");

        cancelTimeout();
        executeExitAction(fromState);
        currentState = toState;

        StateConfig config = stateConfigs.get(toState);
        if (config != null && config.isFinal()) {
            completed = true;
            log.debug("[{}] Reached final state: {}", id, toState);
            executeEntryAction(toState);
            /* Notify AFTER entry action so listeners see final state */
            if (onStateTransition != null) onStateTransition.accept(fromState, toState, event);
            if (onTerminated != null) onTerminated.accept(id);
        } else {
            executeEntryAction(toState);
            /* Notify AFTER entry action so listeners see updated context (e.g. status=UP) */
            if (onStateTransition != null) onStateTransition.accept(fromState, toState, event);
            scheduleTimeout(toState);
        }
    }

    @SuppressWarnings("unchecked")
    private void executeEntryAction(String state) {
        StateConfig config = stateConfigs.get(state);
        if (config == null || config.getEntryAction() == null) return;
        try {
            ((Consumer<GenericStateMachine<TPersistingEntity, TContext>>) (Consumer<?>) config.getEntryAction())
                    .accept(this);
        } catch (Exception e) {
            log.error("[{}] Entry action failed in {}: {}", id, state, e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private void executeExitAction(String state) {
        StateConfig config = stateConfigs.get(state);
        if (config == null || config.getExitAction() == null) return;
        try {
            ((Consumer<GenericStateMachine<TPersistingEntity, TContext>>) (Consumer<?>) config.getExitAction())
                    .accept(this);
        } catch (Exception e) {
            log.error("[{}] Exit action failed in {}: {}", id, state, e.getMessage(), e);
        }
    }

    private void scheduleTimeout(String state) {
        StateConfig config = stateConfigs.get(state);
        if (config == null || !config.hasTimeout()) return;

        String targetState = config.getTimeoutTarget();
        long delayMs = config.getTimeoutMs();

        timeoutFuture = scheduler.schedule(() -> {
            log.debug("[{}] Timeout in state {} -> {}", id, state, targetState);
            fire(new TimeoutEvent(state, targetState));
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void cancelTimeout() {
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
            timeoutFuture = null;
        }
    }

    /**
     * Functional interface for 3-arg callbacks.
     */
    @FunctionalInterface
    public interface TriConsumer<A, B, C> {
        void accept(A a, B b, C c);
    }
}
