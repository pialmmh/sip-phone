package com.telcobright.sipphone.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pure Java RTP session for PCMU (G.711 mu-law).
 * No JNI needed — all encoding/decoding in Java.
 *
 * Audio path:
 *   Mic → PCM (16-bit) → PcmuCodec.encode → RTP packet → UDP → network
 *   Network → UDP → RTP packet → PcmuCodec.decode → PCM (16-bit) → Speaker
 */
public class PcmuRtpSession {

    private static final Logger log = LoggerFactory.getLogger(PcmuRtpSession.class);

    private final String remoteHost;
    private final int remoteRtpPort;
    private final int localRtpPort;

    private DatagramSocket rtpSocket;
    private InetAddress remoteAddress;

    private int sequence;
    private long timestamp;
    private final long ssrc;

    private final AtomicBoolean active = new AtomicBoolean(false);
    private Thread receiveThread;

    public interface FrameReceiver {
        void onFrameReceived(short[] pcm, int samples);
    }

    private FrameReceiver frameReceiver;

    public PcmuRtpSession(String remoteHost, int remoteRtpPort, int localRtpPort) {
        this.remoteHost = remoteHost;
        this.remoteRtpPort = remoteRtpPort;
        this.localRtpPort = localRtpPort;
        this.ssrc = new Random().nextInt() & 0xFFFFFFFFL;
        this.sequence = new Random().nextInt(0xFFFF);
        this.timestamp = new Random().nextInt();
    }

    public void setFrameReceiver(FrameReceiver receiver) {
        this.frameReceiver = receiver;
    }

    public void start() throws Exception {
        remoteAddress = InetAddress.getByName(remoteHost);
        rtpSocket = new DatagramSocket(localRtpPort);
        rtpSocket.setSoTimeout(100); // Non-blocking reads with short timeout
        active.set(true);

        receiveThread = new Thread(this::receiveLoop, "pcmu-rtp-recv");
        receiveThread.setDaemon(true);
        receiveThread.start();

        log.info("PCMU RTP session started: local={} -> {}:{}", localRtpPort, remoteHost, remoteRtpPort);
    }

    public void stop() {
        active.set(false);
        if (receiveThread != null) receiveThread.interrupt();
        if (rtpSocket != null && !rtpSocket.isClosed()) rtpSocket.close();
        log.info("PCMU RTP session stopped");
    }

    /**
     * Send one 20ms frame of PCM audio.
     * PCM (160 samples) → mu-law encode → RTP packet → UDP send.
     */
    public void sendFrame(short[] pcm) {
        if (!active.get() || rtpSocket == null) return;

        byte[] muLawData = PcmuCodec.encode(pcm, 0, PcmuCodec.FRAME_SAMPLES);

        RtpPacket pkt = new RtpPacket();
        pkt.payloadType = PcmuCodec.PAYLOAD_TYPE;
        pkt.sequence = sequence++ & 0xFFFF;
        pkt.timestamp = timestamp;
        pkt.ssrc = ssrc;
        pkt.payload = muLawData;
        pkt.payloadLength = muLawData.length;

        timestamp += PcmuCodec.FRAME_SAMPLES;

        try {
            byte[] data = pkt.toBytes();
            DatagramPacket udpPacket = new DatagramPacket(data, data.length, remoteAddress, remoteRtpPort);
            rtpSocket.send(udpPacket);
        } catch (Exception e) {
            if (active.get()) log.error("RTP send failed: {}", e.getMessage());
        }
    }

    /**
     * Receive loop — reads RTP packets, decodes mu-law, delivers PCM to callback.
     */
    private void receiveLoop() {
        byte[] buf = new byte[1500];

        while (active.get()) {
            try {
                DatagramPacket udpPacket = new DatagramPacket(buf, buf.length);
                rtpSocket.receive(udpPacket);

                RtpPacket rtp = RtpPacket.parse(udpPacket.getData(), udpPacket.getLength());
                if (rtp == null || rtp.payloadLength <= 0) continue;

                short[] pcm = PcmuCodec.decode(rtp.payload, 0, rtp.payloadLength);

                if (frameReceiver != null) {
                    frameReceiver.onFrameReceived(pcm, pcm.length);
                }
            } catch (java.net.SocketTimeoutException e) {
                // Expected — non-blocking timeout, loop continues
            } catch (Exception e) {
                if (active.get()) log.debug("RTP receive: {}", e.getMessage());
            }
        }
    }

    public boolean isActive() { return active.get(); }
}
