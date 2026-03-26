package com.telcobright.sipphone.route;

/**
 * Route endpoint status.
 */
public enum RouteStatus {
    UP,
    DOWN,
    PARTIALLY_AVAILABLE,
    SUSPENDED,
    UNKNOWN;

    public boolean isRoutable() {
        return this == UP || this == PARTIALLY_AVAILABLE;
    }
}
