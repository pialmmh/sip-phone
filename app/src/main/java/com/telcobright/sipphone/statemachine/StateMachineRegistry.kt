package com.telcobright.sipphone.statemachine

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * State machine registry — Android port of State-Walk's AbstractStateMachineRegistry.
 *
 * Manages active state machines by ID. Useful for multi-leg call scenarios
 * where each call leg has its own state machine and events need to be
 * routed to the correct machine.
 */
class StateMachineRegistry {

    companion object {
        private const val TAG = "SMRegistry"
    }

    private val machines = ConcurrentHashMap<String, GenericStateMachine<*, *>>()

    var onMachineRegistered: ((machineId: String) -> Unit)? = null
    var onMachineRemoved: ((machineId: String) -> Unit)? = null

    /**
     * Register a machine.
     */
    fun register(machineId: String, machine: GenericStateMachine<*, *>) {
        machines[machineId] = machine
        machine.onTerminated = { id -> removeMachine(id) }
        Log.d(TAG, "Registered machine: $machineId (active=${machines.size})")
        onMachineRegistered?.invoke(machineId)
    }

    /**
     * Get a machine by ID.
     */
    fun getMachine(machineId: String): GenericStateMachine<*, *>? {
        return machines[machineId]
    }

    /**
     * Get a typed machine by ID.
     */
    @Suppress("UNCHECKED_CAST")
    fun <P, C> getTypedMachine(machineId: String): GenericStateMachine<P, C>? {
        return machines[machineId] as? GenericStateMachine<P, C>
    }

    /**
     * Remove a machine.
     */
    fun removeMachine(machineId: String) {
        val removed = machines.remove(machineId)
        if (removed != null) {
            Log.d(TAG, "Removed machine: $machineId (active=${machines.size})")
            onMachineRemoved?.invoke(machineId)
        }
    }

    /**
     * Fire an event to a specific machine.
     */
    fun fireEvent(machineId: String, event: StateMachineEvent): Boolean {
        val machine = machines[machineId]
        if (machine != null) {
            machine.fire(event)
            return true
        }
        Log.w(TAG, "No machine found for event: $machineId")
        return false
    }

    /**
     * Check if a machine is registered.
     */
    fun isRegistered(machineId: String): Boolean = machines.containsKey(machineId)

    /**
     * Get all active machine IDs.
     */
    fun getActiveMachineIds(): Set<String> = machines.keys.toSet()

    /**
     * Get count of active machines.
     */
    val activeMachineCount: Int get() = machines.size

    /**
     * Shutdown — remove all machines.
     */
    fun shutdown() {
        machines.keys.toList().forEach { removeMachine(it) }
    }
}
