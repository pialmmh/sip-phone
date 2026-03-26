package com.telcobright.sipphone.route.health;

import com.telcobright.sipphone.statemachine.StateMachineEvent;

import java.util.Map;

/**
 * Event for route health state machine.
 * Carries type + optional payload (e.g. error message, latency).
 */
public class RouteHealthEvent implements StateMachineEvent {

    private final RouteHealthEventType type;
    private final String message;
    private final Map<String, String> data;
    private final long timestamp;

    public RouteHealthEvent(RouteHealthEventType type) {
        this(type, null, null);
    }

    public RouteHealthEvent(RouteHealthEventType type, String message) {
        this(type, message, null);
    }

    public RouteHealthEvent(RouteHealthEventType type, String message, Map<String, String> data) {
        this.type = type;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public RouteHealthEventType getType() { return type; }
    public String getMessage() { return message; }
    public Map<String, String> getData() { return data; }

    // StateMachineEvent interface
    @Override public String getEventType() { return type.name(); }
    @Override public String getDescription() { return message != null ? message : type.name(); }
    @Override public Object getPayload() { return data; }
    @Override public long getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return "RouteHealthEvent{" + type + (message != null ? ", " + message : "") + "}";
    }
}
