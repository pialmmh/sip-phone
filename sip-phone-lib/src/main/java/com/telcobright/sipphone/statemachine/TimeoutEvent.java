package com.telcobright.sipphone.statemachine;

/**
 * Timeout event — fired when a state's timeout expires.
 */
public class TimeoutEvent extends GenericEvent {

    private final String sourceState;
    private final String targetState;

    public TimeoutEvent(String sourceState, String targetState) {
        super("TIMEOUT", "Timeout in state " + sourceState + " -> " + targetState);
        this.sourceState = sourceState;
        this.targetState = targetState;
    }

    public String getSourceState() { return sourceState; }
    public String getTargetState() { return targetState; }
}
