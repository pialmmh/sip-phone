package com.telcobright.sipphone.statemachine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * State machine registry — manages active machines by ID.
 * Supports multi-leg call scenarios where each leg has its own machine.
 */
public class StateMachineRegistry {

    private static final Logger log = LoggerFactory.getLogger(StateMachineRegistry.class);

    private final ConcurrentHashMap<String, GenericStateMachine<?, ?>> machines = new ConcurrentHashMap<>();

    private Consumer<String> onMachineRegistered;
    private Consumer<String> onMachineRemoved;

    public void setOnMachineRegistered(Consumer<String> callback) { this.onMachineRegistered = callback; }
    public void setOnMachineRemoved(Consumer<String> callback) { this.onMachineRemoved = callback; }

    public void register(String machineId, GenericStateMachine<?, ?> machine) {
        machines.put(machineId, machine);
        machine.setOnTerminated(id -> removeMachine(id));
        log.debug("Registered machine: {} (active={})", machineId, machines.size());
        if (onMachineRegistered != null) onMachineRegistered.accept(machineId);
    }

    public GenericStateMachine<?, ?> getMachine(String machineId) {
        return machines.get(machineId);
    }

    @SuppressWarnings("unchecked")
    public <P, C> GenericStateMachine<P, C> getTypedMachine(String machineId) {
        return (GenericStateMachine<P, C>) machines.get(machineId);
    }

    public void removeMachine(String machineId) {
        var removed = machines.remove(machineId);
        if (removed != null) {
            log.debug("Removed machine: {} (active={})", machineId, machines.size());
            if (onMachineRemoved != null) onMachineRemoved.accept(machineId);
        }
    }

    public boolean fireEvent(String machineId, StateMachineEvent event) {
        var machine = machines.get(machineId);
        if (machine != null) {
            machine.fire(event);
            return true;
        }
        log.warn("No machine found for event: {}", machineId);
        return false;
    }

    public boolean isRegistered(String machineId) { return machines.containsKey(machineId); }
    public Set<String> getActiveMachineIds() { return Set.copyOf(machines.keySet()); }
    public int getActiveMachineCount() { return machines.size(); }

    public void shutdown() {
        machines.keySet().forEach(this::removeMachine);
    }
}
