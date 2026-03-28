package com.telcobright.sipphone.linux.media;

import com.telcobright.sipphone.bus.EventBus;
import com.telcobright.sipphone.phone.MediaHandler;
import com.telcobright.sipphone.phone.RtcpStatsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

/**
 * Linux media handler — ALL codecs go through native pjmedia transport.
 *
 * PCMU: native mu-law encode/decode + pjmedia RTP/RTCP
 * AMR:  native OpenCORE-AMR encode/decode + pjmedia RTP/RTCP
 *
 * Audio I/O: Java Sound (AmrAudioEngine works for all codecs —
 * it just sends/receives PCM frames via JNI).
 */
public class LinuxMediaHandler implements MediaHandler {

    private static final Logger log = LoggerFactory.getLogger(LinuxMediaHandler.class);

    private final EventBus bus;
    private NativeMediaBridge bridge;
    private AmrAudioEngine audioEngine;  // Works for both AMR and PCMU (sends/receives PCM)

    public LinuxMediaHandler(EventBus bus) {
        this.bus = bus;
    }

    @Override
    public void startMedia(String remoteIp, int remoteRtpPort, int remoteRtcpPort,
                           int localRtpPort, int payloadType, int codecType, String codecName) {
        log.info("Starting media: codec={} (type={}), remote={}:{}, local={}",
                 codecName, codecType, remoteIp, remoteRtpPort, localRtpPort);

        try {
            /* All codecs: PCMU (type=-1), AMR-NB (type=0), AMR-WB (type=1) */
            int sampleRate = (codecType == 1) ? 16000 : 8000;  // AMR-WB=16kHz, rest=8kHz
            int initialMode = (codecType == 1) ? 8 : 7;        // Ignored for PCMU

            /* RTCP quality listener → event bus */
            NativeMediaBridge.QualityListener rtcpListener = (packetLoss, jitter, rtt) -> {
                bus.publish(new RtcpStatsEvent(packetLoss, jitter, rtt));
            };

            bridge = new NativeMediaBridge();
            boolean ok = bridge.nativeCreateRtpSession(
                    remoteIp, remoteRtpPort, remoteRtcpPort,
                    localRtpPort, localRtpPort + 1,
                    new Random().nextInt(), payloadType,
                    codecType, initialMode, false, rtcpListener);

            if (!ok) {
                log.error("Failed to create native RTP session for {}", codecName);
                return;
            }

            audioEngine = new AmrAudioEngine(bridge, sampleRate);
            audioEngine.start();
            log.info("{} media active via pjmedia transport", codecName);

        } catch (Exception e) {
            log.error("Media start failed: {}", e.getMessage(), e);
        }
    }

    @Override
    public void stopMedia() {
        if (audioEngine != null) { audioEngine.stop(); audioEngine = null; }
        if (bridge != null) { bridge.nativeDestroyRtpSession(); bridge = null; }
    }

    @Override
    public void setMuted(boolean muted) {
        if (audioEngine != null) audioEngine.setMuted(muted);
    }
}
