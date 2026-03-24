package com.telcobright.sipphone.statemachine;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * State configuration — entry/exit actions, timeouts, final state flag.
 */
public class StateConfig {

    private final String stateId;
    private boolean isFinal;
    private Consumer<GenericStateMachine<?, ?>> entryAction;
    private Consumer<GenericStateMachine<?, ?>> exitAction;
    private long timeoutDuration;
    private TimeUnit timeoutUnit = TimeUnit.SECONDS;
    private String timeoutTarget;

    public StateConfig(String stateId) {
        this.stateId = stateId;
    }

    public String getStateId() { return stateId; }

    public boolean isFinal() { return isFinal; }
    public void setFinal(boolean value) { this.isFinal = value; }

    public Consumer<GenericStateMachine<?, ?>> getEntryAction() { return entryAction; }
    public void setEntryAction(Consumer<GenericStateMachine<?, ?>> action) { this.entryAction = action; }

    public Consumer<GenericStateMachine<?, ?>> getExitAction() { return exitAction; }
    public void setExitAction(Consumer<GenericStateMachine<?, ?>> action) { this.exitAction = action; }

    public long getTimeoutDuration() { return timeoutDuration; }
    public TimeUnit getTimeoutUnit() { return timeoutUnit; }
    public String getTimeoutTarget() { return timeoutTarget; }

    public void setTimeout(long duration, TimeUnit unit, String target) {
        this.timeoutDuration = duration;
        this.timeoutUnit = unit;
        this.timeoutTarget = target;
    }

    public boolean hasTimeout() {
        return timeoutDuration > 0 && timeoutTarget != null;
    }

    public long getTimeoutMs() {
        return hasTimeout() ? timeoutUnit.toMillis(timeoutDuration) : 0;
    }
}
