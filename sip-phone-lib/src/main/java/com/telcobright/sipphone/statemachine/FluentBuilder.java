package com.telcobright.sipphone.statemachine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Fluent state machine builder — portable Java version of State-Walk's EnhancedFluentBuilder.
 */
public class FluentBuilder<TPersistingEntity, TContext> {

    private final String machineId;
    private String initialStateName = "";
    private final Map<String, StateConfig> stateConfigs = new LinkedHashMap<>();
    private final List<TransitionDef> transitions = new ArrayList<>();
    private final List<StayDef<TPersistingEntity, TContext>> stayDefs = new ArrayList<>();

    private FluentBuilder(String machineId) {
        this.machineId = machineId;
    }

    public static <P, V> FluentBuilder<P, V> create(String machineId) {
        return new FluentBuilder<>(machineId);
    }

    public static <P, V> FluentBuilder<P, V> createForPooling() {
        return new FluentBuilder<>("");
    }

    public FluentBuilder<TPersistingEntity, TContext> initialState(String state) {
        this.initialStateName = state;
        return this;
    }

    public StateBuilder state(String stateName) {
        return new StateBuilder(stateName);
    }

    public GenericStateMachine<TPersistingEntity, TContext> build() {
        var machine = new GenericStateMachine<TPersistingEntity, TContext>(machineId);
        machine.setInitialState(initialStateName);

        stateConfigs.values().forEach(machine::addStateConfig);
        transitions.forEach(t -> machine.addTransition(t.fromState, t.eventClass, t.toState));
        stayDefs.forEach(s -> machine.addStayAction(s.stateId, s.eventClass, s.action));

        /* Wire timeout events as transitions */
        stateConfigs.values().stream()
                .filter(StateConfig::hasTimeout)
                .forEach(c -> machine.addTransition(c.getStateId(), TimeoutEvent.class, c.getTimeoutTarget()));

        return machine;
    }

    public Supplier<GenericStateMachine<TPersistingEntity, TContext>> buildFactory() {
        return this::build;
    }

    /* === Inner builders === */

    public class StateBuilder {
        private final StateConfig config;

        StateBuilder(String stateName) {
            this.config = new StateConfig(stateName);
        }

        @SuppressWarnings("unchecked")
        public StateBuilder onEntry(Consumer<GenericStateMachine<TPersistingEntity, TContext>> action) {
            config.setEntryAction((Consumer<GenericStateMachine<?, ?>>) (Consumer<?>) action);
            return this;
        }

        @SuppressWarnings("unchecked")
        public StateBuilder onExit(Consumer<GenericStateMachine<TPersistingEntity, TContext>> action) {
            config.setExitAction((Consumer<GenericStateMachine<?, ?>>) (Consumer<?>) action);
            return this;
        }

        public StateBuilder timeout(long duration, TimeUnit unit, String targetState) {
            config.setTimeout(duration, unit, targetState);
            return this;
        }

        public TransitionBuilder on(Class<? extends StateMachineEvent> eventType) {
            return new TransitionBuilder(eventType);
        }

        public StateBuilder stay(Class<? extends StateMachineEvent> eventType,
                                 BiConsumer<GenericStateMachine<TPersistingEntity, TContext>, StateMachineEvent> action) {
            stayDefs.add(new StayDef<>(config.getStateId(), eventType, action));
            return this;
        }

        public StateBuilder finalState() {
            config.setFinal(true);
            return this;
        }

        public FluentBuilder<TPersistingEntity, TContext> done() {
            stateConfigs.put(config.getStateId(), config);
            return FluentBuilder.this;
        }

        public class TransitionBuilder {
            private final Class<? extends StateMachineEvent> eventType;

            TransitionBuilder(Class<? extends StateMachineEvent> eventType) {
                this.eventType = eventType;
            }

            public StateBuilder to(String targetState) {
                transitions.add(new TransitionDef(config.getStateId(), eventType, targetState));
                return StateBuilder.this;
            }
        }
    }

    private record TransitionDef(String fromState, Class<? extends StateMachineEvent> eventClass, String toState) {}

    private record StayDef<P, C>(String stateId, Class<? extends StateMachineEvent> eventClass,
                                  BiConsumer<GenericStateMachine<P, C>, StateMachineEvent> action) {}
}
