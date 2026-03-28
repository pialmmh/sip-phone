package com.telcobright.sipphone.linux.media;

import com.telcobright.sipphone.media.PcmuRtpSession;
import com.telcobright.sipphone.phone.PhoneController;
import com.telcobright.sipphone.verto.SdpBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

/**
 * Linux platform media handler — Java Sound audio + JNI for AMR.
 */
public class LinuxMediaHandler implements PhoneController.MediaHandler {

    private static final Logger log = LoggerFactory.getLogger(LinuxMediaHandler.class);

    private PcmuRtpSession pcmuRtpSession;
    private PcmuAudioEngine pcmuAudioEngine;
    private NativeMediaBridge amrBridge;
    private AmrAudioEngine amrAudioEngine;

    @Override
    public void startMedia(String remoteIp, int remoteRtpPort, int remoteRtcpPort,
                           int localRtpPort, int payloadType, int codecType, String codecName) {
        log.info("Starting media: codec={}, remote={}:{}, local={}", codecName, remoteIp, remoteRtpPort, localRtpPort);

        if (SdpBuilder.CODEC_PCMU.equals(codecName)) {
            startPcmu(remoteIp, remoteRtpPort, localRtpPort);
        } else {
            startAmr(remoteIp, remoteRtpPort, remoteRtcpPort, localRtpPort, payloadType, codecType);
        }
    }

    @Override
    public void stopMedia() {
        if (pcmuAudioEngine != null) { pcmuAudioEngine.stop(); pcmuAudioEngine = null; }
        if (pcmuRtpSession != null) { pcmuRtpSession.stop(); pcmuRtpSession = null; }
        if (amrAudioEngine != null) { amrAudioEngine.stop(); amrAudioEngine = null; }
        if (amrBridge != null) { amrBridge.nativeDestroyRtpSession(); amrBridge = null; }
    }

    @Override
    public void setMuted(boolean muted) {
        if (pcmuAudioEngine != null) pcmuAudioEngine.setMuted(muted);
        if (amrAudioEngine != null) amrAudioEngine.setMuted(muted);
    }

    private void startPcmu(String remoteIp, int remoteRtpPort, int localRtpPort) {
        try {
            pcmuRtpSession = new PcmuRtpSession(remoteIp, remoteRtpPort, localRtpPort);
            pcmuAudioEngine = new PcmuAudioEngine(pcmuRtpSession);
            pcmuRtpSession.start();
            pcmuAudioEngine.start();
            log.info("PCMU media active");
        } catch (Exception e) {
            log.error("PCMU start failed: {}", e.getMessage(), e);
        }
    }

    private void startAmr(String remoteIp, int remoteRtpPort, int remoteRtcpPort,
                          int localRtpPort, int payloadType, int codecType) {
        try {
            int sampleRate = (codecType == 1) ? 16000 : 8000;
            int initialMode = (codecType == 1) ? 8 : 7;

            amrBridge = new NativeMediaBridge();
            boolean ok = amrBridge.nativeCreateRtpSession(
                    remoteIp, remoteRtpPort, remoteRtcpPort,
                    localRtpPort, localRtpPort + 1,
                    new Random().nextInt(), payloadType,
                    codecType, initialMode, false, null);

            if (!ok) {
                log.error("Failed to create native RTP session");
                return;
            }

            amrAudioEngine = new AmrAudioEngine(amrBridge, sampleRate);
            amrAudioEngine.start();
            log.info("AMR media active (type={})", codecType == 1 ? "WB" : "NB");
        } catch (Exception e) {
            log.error("AMR start failed: {}", e.getMessage(), e);
        }
    }
}
