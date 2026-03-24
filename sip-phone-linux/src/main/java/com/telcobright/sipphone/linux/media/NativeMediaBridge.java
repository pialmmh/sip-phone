package com.telcobright.sipphone.linux.media;

/**
 * JNI bridge to native RTP/RTCP + AMR codec.
 * Audio I/O is handled in Java (JavaSoundAudioEngine) — the native layer
 * only does encode/decode/RTP, not audio capture/playback.
 */
public class NativeMediaBridge {

    public interface QualityListener {
        void onQualityUpdate(float packetLossPercent, float jitterMs, float rttMs);
    }

    static {
        System.loadLibrary("sipphone-native");
    }

    public native boolean nativeCreateRtpSession(
            String remoteHost, int remoteRtpPort, int remoteRtcpPort,
            int localRtpPort, int localRtcpPort,
            int ssrc, int payloadType,
            int codecType, int initialMode, boolean dtx,
            QualityListener qualityListener);

    public native void nativeDestroyRtpSession();

    /** Send one 20ms PCM frame (160 samples for NB, 320 for WB). Returns 0 on success. */
    public native int nativeSendFrame(short[] pcmData, int cmr);

    /** Receive one decoded PCM frame. Returns number of samples, or 0 if none available. */
    public native int nativeReceiveFrame(short[] pcmOut, int[] cmrOut);

    public native void nativeSetMode(int mode);

    public native void nativeSendRtcp();
}
