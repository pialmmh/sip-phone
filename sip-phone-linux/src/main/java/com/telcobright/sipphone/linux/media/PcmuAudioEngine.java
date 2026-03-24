package com.telcobright.sipphone.linux.media;

import com.telcobright.sipphone.media.PcmuCodec;
import com.telcobright.sipphone.media.PcmuRtpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Complete PCMU audio engine for Linux.
 *
 * Audio routing:
 *   Mic (TargetDataLine) → 16-bit PCM → PcmuRtpSession.sendFrame() → mu-law → RTP → UDP
 *   UDP → RTP → mu-law → PcmuRtpSession callback → 16-bit PCM → Speaker (SourceDataLine)
 */
public class PcmuAudioEngine implements PcmuRtpSession.FrameReceiver {

    private static final Logger log = LoggerFactory.getLogger(PcmuAudioEngine.class);

    private final PcmuRtpSession rtpSession;
    private TargetDataLine captureLine;
    private SourceDataLine playbackLine;
    private Thread captureThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean muted = new AtomicBoolean(false);

    public PcmuAudioEngine(PcmuRtpSession rtpSession) {
        this.rtpSession = rtpSession;
        this.rtpSession.setFrameReceiver(this);
    }

    public void start() throws LineUnavailableException {
        AudioFormat format = new AudioFormat(PcmuCodec.SAMPLE_RATE, 16, 1, true, false);

        /* Mic capture */
        DataLine.Info captureInfo = new DataLine.Info(TargetDataLine.class, format);
        captureLine = (TargetDataLine) AudioSystem.getLine(captureInfo);
        captureLine.open(format, PcmuCodec.FRAME_SAMPLES * 4);
        captureLine.start();

        /* Speaker playback */
        DataLine.Info playbackInfo = new DataLine.Info(SourceDataLine.class, format);
        playbackLine = (SourceDataLine) AudioSystem.getLine(playbackInfo);
        playbackLine.open(format, PcmuCodec.FRAME_SAMPLES * 4);
        playbackLine.start();

        running.set(true);

        captureThread = new Thread(this::captureLoop, "pcmu-capture");
        captureThread.setDaemon(true);
        captureThread.start();

        log.info("PCMU audio engine started (8kHz mono)");
    }

    public void stop() {
        running.set(false);
        if (captureThread != null) captureThread.interrupt();
        if (captureLine != null) { captureLine.stop(); captureLine.close(); }
        if (playbackLine != null) { playbackLine.stop(); playbackLine.close(); }
        log.info("PCMU audio engine stopped");
    }

    public void setMuted(boolean mute) { muted.set(mute); }

    /**
     * Capture loop: Mic → PCM → RTP send (20ms frames).
     */
    private void captureLoop() {
        byte[] byteBuffer = new byte[PcmuCodec.FRAME_SAMPLES * 2]; // 16-bit = 2 bytes
        short[] pcmFrame = new short[PcmuCodec.FRAME_SAMPLES];

        while (running.get()) {
            int bytesRead = captureLine.read(byteBuffer, 0, byteBuffer.length);
            if (bytesRead <= 0) continue;
            if (muted.get()) continue;

            ByteBuffer.wrap(byteBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcmFrame);
            rtpSession.sendFrame(pcmFrame);
        }
    }

    /**
     * Callback from PcmuRtpSession: received decoded PCM → play to speaker.
     */
    @Override
    public void onFrameReceived(short[] pcm, int samples) {
        if (!running.get() || playbackLine == null) return;

        byte[] byteBuffer = new byte[samples * 2];
        ByteBuffer.wrap(byteBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, 0, samples);
        playbackLine.write(byteBuffer, 0, samples * 2);
    }

    public boolean isRunning() { return running.get(); }
}
