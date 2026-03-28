package com.telcobright.sipphone.phone;

/**
 * RTCP quality stats event — published on EventBus by media handler.
 * Consumed by CallLogger for logging and by UiStateMachine for display.
 */
public record RtcpStatsEvent(float packetLossPercent, float jitterMs, float rttMs) {}
