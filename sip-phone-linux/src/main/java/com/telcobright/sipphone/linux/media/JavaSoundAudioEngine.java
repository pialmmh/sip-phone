package com.telcobright.sipphone.linux.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Audio engine using Java Sound API (javax.sound.sampled).
 * Captures mic → sends PCM to RTP encoder.
 * Receives decoded PCM from RTP decoder → plays to speaker.
 */
public class JavaSoundAudioEngine {

    private static final Logger log = LoggerFactory.getLogger(JavaSoundAudioEngine.class);

    private final NativeMediaBridge bridge;
    private final int sampleRate;
    private final int frameSamples; // 160 (NB) or 320 (WB)

    private TargetDataLine captureLine;
    private SourceDataLine playbackLine;
    private Thread captureThread;
    private Thread playbackThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean muted = new AtomicBoolean(false);

    public JavaSoundAudioEngine(NativeMediaBridge bridge, int sampleRate) {
        this.bridge = bridge;
        this.sampleRate = sampleRate;
        this.frameSamples = sampleRate / 50; // 20ms frame
    }

    public void start() throws LineUnavailableException {
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);

        /* Capture (microphone) */
        DataLine.Info captureInfo = new DataLine.Info(TargetDataLine.class, format);
        captureLine = (TargetDataLine) AudioSystem.getLine(captureInfo);
        captureLine.open(format, frameSamples * 4); // 2x buffer
        captureLine.start();

        /* Playback (speaker) */
        DataLine.Info playbackInfo = new DataLine.Info(SourceDataLine.class, format);
        playbackLine = (SourceDataLine) AudioSystem.getLine(playbackInfo);
        playbackLine.open(format, frameSamples * 4);
        playbackLine.start();

        running.set(true);

        captureThread = new Thread(this::captureLoop, "audio-capture");
        captureThread.setDaemon(true);
        captureThread.start();

        playbackThread = new Thread(this::playbackLoop, "audio-playback");
        playbackThread.setDaemon(true);
        playbackThread.start();

        log.info("Audio engine started: {}Hz, frame={} samples", sampleRate, frameSamples);
    }

    public void stop() {
        running.set(false);

        if (captureThread != null) captureThread.interrupt();
        if (playbackThread != null) playbackThread.interrupt();

        if (captureLine != null) { captureLine.stop(); captureLine.close(); }
        if (playbackLine != null) { playbackLine.stop(); playbackLine.close(); }

        log.info("Audio engine stopped");
    }

    public void setMuted(boolean mute) {
        muted.set(mute);
    }

    private void captureLoop() {
        byte[] byteBuffer = new byte[frameSamples * 2]; // 16-bit = 2 bytes/sample
        short[] pcmFrame = new short[frameSamples];

        while (running.get()) {
            int bytesRead = captureLine.read(byteBuffer, 0, byteBuffer.length);
            if (bytesRead <= 0) continue;

            if (muted.get()) continue;

            /* Convert byte[] to short[] (little-endian) */
            ByteBuffer.wrap(byteBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcmFrame);

            /* Send to native RTP encoder */
            bridge.nativeSendFrame(pcmFrame, -1);
        }
    }

    private void playbackLoop() {
        short[] pcmFrame = new short[frameSamples];
        byte[] byteBuffer = new byte[frameSamples * 2];
        int[] cmrOut = new int[1];

        while (running.get()) {
            int samples = bridge.nativeReceiveFrame(pcmFrame, cmrOut);
            if (samples <= 0) {
                /* No data — send silence to keep timing */
                try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                continue;
            }

            /* Convert short[] to byte[] (little-endian) */
            ByteBuffer.wrap(byteBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcmFrame, 0, samples);

            playbackLine.write(byteBuffer, 0, samples * 2);
        }
    }
}
