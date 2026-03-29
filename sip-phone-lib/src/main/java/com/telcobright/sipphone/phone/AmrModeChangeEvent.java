package com.telcobright.sipphone.phone;

/**
 * Published when adaptive bitrate controller decides to change AMR mode.
 * Consumed by media handler to call nativeSetMode() + set CMR.
 */
public record AmrModeChangeEvent(int mode, String label, float triggerLoss, float triggerJitter) {}
