package com.telcobright.sipphone.phone;

/**
 * Events from route health → UiStateMachine via event bus.
 * Registration lifecycle is handled by route health, not call machine.
 */
public sealed interface RouteEvent {
    record Connecting(String routeId) implements RouteEvent {}
    record Registered(String routeId) implements RouteEvent {}
    record Disconnected(String routeId, String reason) implements RouteEvent {}
    record Reconnecting(String routeId, long delayMs) implements RouteEvent {}
}
