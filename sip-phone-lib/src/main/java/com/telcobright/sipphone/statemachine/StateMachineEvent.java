package com.telcobright.sipphone.statemachine;

/**
 * State machine event interface — portable version of State-Walk's StateMachineEvent.
 */
public interface StateMachineEvent {
    String getEventType();
    String getDescription();
    Object getPayload();
    long getTimestamp();
}
