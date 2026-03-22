package com.telcobright.sipphone.statemachine

import java.util.concurrent.TimeUnit

/**
 * Fluent state machine builder — Android port of State-Walk's EnhancedFluentBuilder.
 *
 * Usage:
 * ```
 * FluentBuilder.create<MyEntity, MyContext>("machine-1")
 *     .initialState("IDLE")
 *     .state("IDLE")
 *         .onEntry { machine -> ... }
 *         .on(SomeEvent::class.java).to("NEXT_STATE")
 *         .timeout(30, TimeUnit.SECONDS, "FAILED")
 *     .done()
 *     .state("FAILED")
 *         .finalState()
 *     .done()
 *     .build()
 * ```
 */
class FluentBuilder<TPersistingEntity, TContext> private constructor(
    private val machineId: String,
    private val forPooling: Boolean
) {
    companion object {
        fun <P, V> create(machineId: String): FluentBuilder<P, V> {
            return FluentBuilder(machineId, false)
        }

        fun <P, V> createForPooling(): FluentBuilder<P, V> {
            return FluentBuilder("", true)
        }
    }

    private var initialStateName: String = ""
    private val stateConfigs = mutableMapOf<String, StateConfig>()
    private val transitions = mutableListOf<Triple<String, Class<out StateMachineEvent>, String>>()
    private val stayActions = mutableListOf<Triple<String, Class<out StateMachineEvent>,
        (GenericStateMachine<TPersistingEntity, TContext>, StateMachineEvent) -> Unit>>()

    fun initialState(state: String): FluentBuilder<TPersistingEntity, TContext> {
        initialStateName = state
        return this
    }

    fun state(stateName: String): StateBuilder {
        return StateBuilder(stateName)
    }

    fun build(): GenericStateMachine<TPersistingEntity, TContext> {
        val machine = GenericStateMachine<TPersistingEntity, TContext>(machineId)
        machine.setInitialState(initialStateName)

        stateConfigs.values.forEach { machine.addStateConfig(it) }
        transitions.forEach { (from, event, to) -> machine.addTransition(from, event, to) }
        stayActions.forEach { (state, event, action) -> machine.addStayAction(state, event, action) }

        /* Wire timeout events as transitions */
        stateConfigs.values.filter { it.hasTimeout }.forEach { config ->
            machine.addTransition(config.stateId, TimeoutEvent::class.java, config.timeoutTarget!!)
        }

        return machine
    }

    /**
     * Build a factory function that creates new machines from this template.
     */
    fun buildFactory(): () -> GenericStateMachine<TPersistingEntity, TContext> {
        return { build() }
    }

    inner class StateBuilder(private val stateName: String) {
        private val config = StateConfig(stateName)

        fun onEntry(action: (GenericStateMachine<TPersistingEntity, TContext>) -> Unit): StateBuilder {
            @Suppress("UNCHECKED_CAST")
            config.entryAction = action as (GenericStateMachine<*, *>) -> Unit
            return this
        }

        fun onExit(action: (GenericStateMachine<TPersistingEntity, TContext>) -> Unit): StateBuilder {
            @Suppress("UNCHECKED_CAST")
            config.exitAction = action as (GenericStateMachine<*, *>) -> Unit
            return this
        }

        fun timeout(duration: Long, unit: TimeUnit, targetState: String): StateBuilder {
            config.timeoutDuration = duration
            config.timeoutUnit = unit
            config.timeoutTarget = targetState
            return this
        }

        fun on(eventType: Class<out StateMachineEvent>): TransitionBuilder {
            return TransitionBuilder(eventType)
        }

        fun stay(
            eventType: Class<out StateMachineEvent>,
            action: (GenericStateMachine<TPersistingEntity, TContext>, StateMachineEvent) -> Unit
        ): StateBuilder {
            stayActions.add(Triple(stateName, eventType, action))
            return this
        }

        fun finalState(): StateBuilder {
            config.isFinal = true
            return this
        }

        fun done(): FluentBuilder<TPersistingEntity, TContext> {
            stateConfigs[stateName] = config
            return this@FluentBuilder
        }

        inner class TransitionBuilder(private val eventType: Class<out StateMachineEvent>) {
            fun to(targetState: String): StateBuilder {
                transitions.add(Triple(stateName, eventType, targetState))
                return this@StateBuilder
            }
        }
    }
}
