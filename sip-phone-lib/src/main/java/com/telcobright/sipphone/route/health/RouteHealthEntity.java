package com.telcobright.sipphone.route.health;

import java.time.LocalDateTime;

/**
 * Minimal entity for route health state machine.
 * Not persisted — just satisfies GenericStateMachine's TPersistingEntity type parameter.
 */
public class RouteHealthEntity {

    private String id;
    private LocalDateTime createdAt;
    private boolean complete;
    private String currentState;
    private LocalDateTime lastStateChange;

    public RouteHealthEntity() {}

    public RouteHealthEntity(String id) {
        this.id = id;
        this.createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public boolean isComplete() { return complete; }
    public void markComplete() { this.complete = true; }

    public String getCurrentState() { return currentState; }
    public void setCurrentState(String state) { this.currentState = state; }

    public LocalDateTime getLastStateChange() { return lastStateChange; }
    public void setLastStateChange(LocalDateTime lastStateChange) { this.lastStateChange = lastStateChange; }

    public void reset() {
        this.createdAt = LocalDateTime.now();
        this.complete = false;
        this.currentState = null;
        this.lastStateChange = null;
    }
}
