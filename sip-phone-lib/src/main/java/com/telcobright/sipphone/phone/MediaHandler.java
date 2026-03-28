package com.telcobright.sipphone.phone;

/**
 * Platform-specific media handler.
 *
 * Implementations:
 *   Linux:   LinuxMediaHandler (Java Sound + JNI)
 *   Android: AndroidMediaHandler (Oboe + JNI)
 *   iOS:     IosMediaHandler (Core Audio + C bridge)
 */
public interface MediaHandler {
    void startMedia(String remoteIp, int remoteRtpPort, int remoteRtcpPort,
                    int localRtpPort, int payloadType, int codecType, String codecName);
    void stopMedia();
    void setMuted(boolean muted);
}
