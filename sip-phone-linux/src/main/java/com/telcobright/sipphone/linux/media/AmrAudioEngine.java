package com.telcobright.sipphone.linux.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AMR audio engine for Linux.
 * Uses NativeMediaBridge (JNI) for AMR encode/decode + RTP.
 * Java Sound API for mic capture and speaker playback.
 *
 * Audio routing:
 *   Mic (TargetDataLine) → 16-bit PCM → JNI nativeSendFrame() → native AMR encode → native RTP → UDP
 *   UDP → native RTP → native AMR decode → JNI nativeReceiveFrame() → 16-bit PCM → Speaker (SourceDataLine)
 */
public class AmrAudioEngine {

    private static final Logger log = LoggerFactory.getLogger(AmrAudioEngine.class);

    private final NativeMediaBridge bridge;
    private final int sampleRate;
    private final int frameSamples;   // 160 (NB/8kHz) or 320 (WB/16kHz)

    private TargetDataLine captureLine;
    private SourceDataLine playbackLine;
    private Thread captureThread;
    private Thread playbackThread;
    private ScheduledExecutorService rtcpScheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean muted = new AtomicBoolean(false);

    /**
     * @param bridge     JNI bridge (RTP session must be created before start())
     * @param sampleRate 8000 for AMR-NB, 16000 for AMR-WB
     */
    public AmrAudioEngine(NativeMediaBridge bridge, int sampleRate) {
        this.bridge = bridge;
        this.sampleRate = sampleRate;
        this.frameSamples = sampleRate / 50; // 20ms frame
    }

    public void start() throws LineUnavailableException {
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);

        /* Mic capture */
        DataLine.Info captureInfo = new DataLine.Info(TargetDataLine.class, format);
        captureLine = (TargetDataLine) AudioSystem.getLine(captureInfo);
        captureLine.open(format, frameSamples * 4);
        captureLine.start();

        /* Speaker playback */
        DataLine.Info playbackInfo = new DataLine.Info(SourceDataLine.class, format);
        playbackLine = (SourceDataLine) AudioSystem.getLine(playbackInfo);
        playbackLine.open(format, frameSamples * 4);
        playbackLine.start();

        running.set(true);

        captureThread = new Thread(this::captureLoop, "amr-capture");
        captureThread.setDaemon(true);
        captureThread.start();

        playbackThread = new Thread(this::playbackLoop, "amr-playback");
        playbackThread.setDaemon(true);
        playbackThread.start();

        /* Send RTCP reports every 5 seconds */
        rtcpScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "amr-rtcp");
            t.setDaemon(true);
            return t;
        });
        rtcpScheduler.scheduleAtFixedRate(() -> bridge.nativeSendRtcp(),
                5, 5, TimeUnit.SECONDS);

        log.info("AMR audio engine started: {}Hz, frame={} samples", sampleRate, frameSamples);
    }

    public void stop() {
        running.set(false);
        if (captureThread != null) captureThread.interrupt();
        if (playbackThread != null) playbackThread.interrupt();
        if (rtcpScheduler != null) rtcpScheduler.shutdownNow();
        if (captureLine != null) { captureLine.stop(); captureLine.close(); }
        if (playbackLine != null) { playbackLine.stop(); playbackLine.close(); }
        log.info("AMR audio engine stopped");
    }

    public void setMuted(boolean mute) { muted.set(mute); }
    public boolean isRunning() { return running.get(); }

    /**
     * Capture loop: Mic → PCM → JNI native AMR encode → RTP send.
     */
    private void captureLoop() {
        byte[] byteBuffer = new byte[frameSamples * 2];
        short[] pcmFrame = new short[frameSamples];

        while (running.get()) {
            int bytesRead = captureLine.read(byteBuffer, 0, byteBuffer.length);
            if (bytesRead <= 0) continue;
            if (muted.get()) continue;

            ByteBuffer.wrap(byteBuffer).order(ByteOrder.LITTLE_ENDIAN)
                      .asShortBuffer().get(pcmFrame);

            /* JNI: PCM → AMR encode → RTP send (all in native) */
            bridge.nativeSendFrame(pcmFrame, -1);
        }
    }

    /**
     * Playback loop: JNI native RTP receive → AMR decode → PCM → Speaker.
     */
    private void playbackLoop() {
        short[] pcmFrame = new short[frameSamples];
        byte[] byteBuffer = new byte[frameSamples * 2];
        int[] cmrOut = new int[1];

        while (running.get()) {
            /* JNI: RTP receive → AMR decode → PCM (all in native) */
            int samples = bridge.nativeReceiveFrame(pcmFrame, cmrOut);
            if (samples <= 0) {
                try { Thread.sleep(5); } catch (InterruptedException e) { break; }
                continue;
            }

            ByteBuffer.wrap(byteBuffer).order(ByteOrder.LITTLE_ENDIAN)
                      .asShortBuffer().put(pcmFrame, 0, samples);
            playbackLine.write(byteBuffer, 0, samples * 2);
        }
    }
}
