package com.telcobright.sipphone.statemachine;

/**
 * Base event implementation.
 */
public class GenericEvent implements StateMachineEvent {

    private final String eventType;
    private final String description;
    private final Object payload;
    private final long timestamp;

    public GenericEvent(String eventType) {
        this(eventType, "", null);
    }

    public GenericEvent(String eventType, String description) {
        this(eventType, description, null);
    }

    public GenericEvent(String eventType, String description, Object payload) {
        this.eventType = eventType;
        this.description = description;
        this.payload = payload;
        this.timestamp = System.currentTimeMillis();
    }

    @Override public String getEventType() { return eventType; }
    @Override public String getDescription() { return description; }
    @Override public Object getPayload() { return payload; }
    @Override public long getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return eventType + (description.isEmpty() ? "" : ": " + description);
    }
}
