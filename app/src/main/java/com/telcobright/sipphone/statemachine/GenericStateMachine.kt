package com.telcobright.sipphone.statemachine

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight state machine — Android port of State-Walk's GenericStateMachine.
 *
 * Supports:
 * - Typed persistent entity + volatile context (dual-context model)
 * - Fluent builder API
 * - Synchronized transitions
 * - State entry/exit actions
 * - Per-state timeouts
 * - Pooling (resetForReuse)
 * - Registry integration
 *
 * @param TPersistingEntity  Persistent context (survives across states)
 * @param TContext           Volatile context (runtime-only data)
 */
class GenericStateMachine<TPersistingEntity, TContext>(
    var id: String = ""
) {
    companion object {
        private const val TAG = "StateMachine"
    }

    var currentState: String = ""
        private set

    var persistingEntity: TPersistingEntity? = null
    var context: TContext? = null

    private val stateConfigs = ConcurrentHashMap<String, StateConfig>()
    private val transitions = ConcurrentHashMap<String, MutableMap<Class<out StateMachineEvent>, String>>()
    private val stayActions = ConcurrentHashMap<String, MutableMap<Class<out StateMachineEvent>,
        (GenericStateMachine<TPersistingEntity, TContext>, StateMachineEvent) -> Unit>>()

    private var initialStateName: String = ""
    private var isStarted = false
    private var isCompleted = false

    var onStateTransition: ((fromState: String, toState: String, event: StateMachineEvent?) -> Unit)? = null
    var onTerminated: ((machineId: String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    /* Configuration methods (called by builder) */

    internal fun setInitialState(state: String) {
        initialStateName = state
        currentState = state
    }

    internal fun addStateConfig(config: StateConfig) {
        stateConfigs[config.stateId] = config
    }

    internal fun addTransition(fromState: String, eventClass: Class<out StateMachineEvent>, toState: String) {
        transitions.getOrPut(fromState) { mutableMapOf() }[eventClass] = toState
    }

    internal fun addStayAction(
        stateId: String,
        eventClass: Class<out StateMachineEvent>,
        action: (GenericStateMachine<TPersistingEntity, TContext>, StateMachineEvent) -> Unit
    ) {
        stayActions.getOrPut(stateId) { mutableMapOf() }[eventClass] = action
    }

    /* Public API */

    /**
     * Start the machine — executes entry action of initial state.
     */
    fun start() {
        if (isStarted) return
        isStarted = true
        currentState = initialStateName
        Log.d(TAG, "[$id] Started in state: $currentState")
        executeEntryAction(currentState)
        scheduleTimeout(currentState)
    }

    /**
     * Fire an event — triggers transition if matching rule exists.
     */
    @Synchronized
    fun fire(event: StateMachineEvent) {
        if (isCompleted) {
            Log.w(TAG, "[$id] Event ${event.eventType} ignored — machine completed")
            return
        }

        val stateTransitions = transitions[currentState]
        val targetState = stateTransitions?.entries?.find { (eventClass, _) ->
            eventClass.isInstance(event)
        }?.value

        if (targetState != null) {
            performTransition(targetState, event)
        } else {
            /* Check stay actions */
            val stateStays = stayActions[currentState]
            val stayAction = stateStays?.entries?.find { (eventClass, _) ->
                eventClass.isInstance(event)
            }?.value

            if (stayAction != null) {
                Log.d(TAG, "[$id] Stay action in $currentState for ${event.eventType}")
                stayAction(this, event)
            } else {
                Log.d(TAG, "[$id] Event ${event.eventType} ignored in state $currentState")
            }
        }
    }

    /**
     * Programmatic transition (from within entry/exit actions).
     */
    @Synchronized
    fun transitionTo(newState: String) {
        if (isCompleted) return
        performTransition(newState, null)
    }

    fun isInState(state: String): Boolean = currentState == state
    fun isComplete(): Boolean = isCompleted
    fun isActive(): Boolean = isStarted && !isCompleted

    /**
     * Reset for object pool reuse.
     */
    fun resetForReuse() {
        cancelTimeout()
        currentState = initialStateName
        persistingEntity = null
        context = null
        isStarted = false
        isCompleted = false
        id = ""
    }

    /* Internal */

    private fun performTransition(toState: String, event: StateMachineEvent?) {
        val fromState = currentState

        Log.d(TAG, "[$id] $fromState -> $toState" +
            (if (event != null) " (${event.eventType})" else ""))

        cancelTimeout()
        executeExitAction(fromState)

        currentState = toState
        onStateTransition?.invoke(fromState, toState, event)

        val config = stateConfigs[toState]
        if (config?.isFinal == true) {
            isCompleted = true
            Log.d(TAG, "[$id] Reached final state: $toState")
            executeEntryAction(toState)
            onTerminated?.invoke(id)
        } else {
            executeEntryAction(toState)
            scheduleTimeout(toState)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun executeEntryAction(state: String) {
        val config = stateConfigs[state] ?: return
        try {
            (config.entryAction as? ((GenericStateMachine<TPersistingEntity, TContext>) -> Unit))
                ?.invoke(this)
        } catch (e: Exception) {
            Log.e(TAG, "[$id] Entry action failed in $state: ${e.message}", e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun executeExitAction(state: String) {
        val config = stateConfigs[state] ?: return
        try {
            (config.exitAction as? ((GenericStateMachine<TPersistingEntity, TContext>) -> Unit))
                ?.invoke(this)
        } catch (e: Exception) {
            Log.e(TAG, "[$id] Exit action failed in $state: ${e.message}", e)
        }
    }

    private fun scheduleTimeout(state: String) {
        val config = stateConfigs[state] ?: return
        if (!config.hasTimeout) return

        val targetState = config.timeoutTarget ?: return
        val delayMs = config.timeoutMs

        timeoutRunnable = Runnable {
            Log.d(TAG, "[$id] Timeout in state $state -> $targetState")
            fire(TimeoutEvent(state, targetState))
        }
        handler.postDelayed(timeoutRunnable!!, delayMs)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }
}
