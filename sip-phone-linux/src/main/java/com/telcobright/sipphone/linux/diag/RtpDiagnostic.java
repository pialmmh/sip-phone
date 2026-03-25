package com.telcobright.sipphone.linux.diag;

import com.telcobright.sipphone.media.PcmuCodec;
import com.telcobright.sipphone.media.PcmuRtpSession;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RTP diagnostic — tests send/receive paths independently.
 *
 * Usage: java -cp ... RtpDiagnostic loopback
 *        java -cp ... RtpDiagnostic send <remoteIp> <remotePort> <localPort>
 *        java -cp ... RtpDiagnostic recv <localPort>
 */
public class RtpDiagnostic {

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "loopback".equals(args[0])) {
            testLoopback();
        } else if ("send".equals(args[0]) && args.length >= 4) {
            testSend(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]));
        } else if ("recv".equals(args[0]) && args.length >= 2) {
            testReceive(Integer.parseInt(args[1]));
        } else {
            System.out.println("Usage: RtpDiagnostic [loopback|send <ip> <port> <localPort>|recv <port>]");
        }
    }

    /**
     * Loopback test: send RTP to ourselves and verify we receive it.
     * Tests: PcmuCodec encode/decode + RtpPacket serialize/parse + UDP send/recv
     */
    private static void testLoopback() throws Exception {
        System.out.println("=== RTP Loopback Test ===");

        int port = 15000;
        AtomicInteger received = new AtomicInteger(0);

        PcmuRtpSession session = new PcmuRtpSession("127.0.0.1", port, port);
        session.setFrameReceiver((pcm, samples) -> {
            received.incrementAndGet();
            /* Check if audio is non-silent */
            boolean hasSamples = false;
            for (int i = 0; i < samples; i++) {
                if (pcm[i] != 0) { hasSamples = true; break; }
            }
            if (received.get() <= 3) {
                System.out.printf("  Frame %d: %d samples, hasAudio=%s, first5=[%d,%d,%d,%d,%d]%n",
                    received.get(), samples, hasSamples, pcm[0], pcm[1], pcm[2], pcm[3], pcm[4]);
            }
        });
        session.start();

        /* Send 10 test frames with a sine wave tone */
        System.out.println("Sending 10 PCMU frames to 127.0.0.1:" + port + "...");
        for (int f = 0; f < 10; f++) {
            short[] pcm = new short[160];
            for (int i = 0; i < 160; i++) {
                pcm[i] = (short) (16000 * Math.sin(2 * Math.PI * 440 * (f * 160 + i) / 8000.0));
            }
            session.sendFrame(pcm);
            Thread.sleep(20);
        }

        Thread.sleep(200); // Wait for receive
        session.stop();

        System.out.printf("%nResult: sent=10, received=%d%n", received.get());
        if (received.get() >= 8) {
            System.out.println("PASS — RTP loopback works");
        } else {
            System.out.println("FAIL — expected >=8 received frames");
        }

        /* Also test codec directly */
        System.out.println("\n=== PCMU Codec Test ===");
        short[] original = new short[160];
        for (int i = 0; i < 160; i++) {
            original[i] = (short) (16000 * Math.sin(2 * Math.PI * 440 * i / 8000.0));
        }
        byte[] encoded = PcmuCodec.encode(original, 0, 160);
        short[] decoded = PcmuCodec.decode(encoded, 0, 160);

        double maxError = 0;
        for (int i = 0; i < 160; i++) {
            double err = Math.abs(original[i] - decoded[i]);
            if (err > maxError) maxError = err;
        }
        System.out.printf("Codec: maxError=%.0f (should be <500 for mu-law quantization)%n", maxError);
        System.out.println(maxError < 500 ? "PASS" : "FAIL");
    }

    /**
     * Send test tone to a remote RTP endpoint.
     */
    private static void testSend(String remoteIp, int remotePort, int localPort) throws Exception {
        System.out.printf("=== RTP Send Test: local:%d -> %s:%d ===%n", localPort, remoteIp, remotePort);

        PcmuRtpSession session = new PcmuRtpSession(remoteIp, remotePort, localPort);
        session.start();

        System.out.println("Sending 250 frames (5 seconds) of 440Hz tone...");
        for (int f = 0; f < 250; f++) {
            short[] pcm = new short[160];
            for (int i = 0; i < 160; i++) {
                pcm[i] = (short) (16000 * Math.sin(2 * Math.PI * 440 * (f * 160 + i) / 8000.0));
            }
            session.sendFrame(pcm);
            Thread.sleep(20);
        }

        session.stop();
        System.out.println("Done — 250 PCMU frames sent.");
    }

    /**
     * Listen for incoming RTP and report.
     */
    private static void testReceive(int localPort) throws Exception {
        System.out.printf("=== RTP Receive Test: listening on port %d ===%n", localPort);

        DatagramSocket socket = new DatagramSocket(localPort);
        socket.setSoTimeout(10000);
        byte[] buf = new byte[1500];
        int count = 0;

        System.out.println("Waiting for RTP packets (10s timeout)...");
        try {
            while (count < 50) {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                socket.receive(pkt);
                count++;
                if (count <= 5 || count % 25 == 0) {
                    System.out.printf("  Packet %d: %d bytes from %s:%d%n",
                        count, pkt.getLength(), pkt.getAddress().getHostAddress(), pkt.getPort());
                }
            }
        } catch (java.net.SocketTimeoutException e) {
            System.out.println("  Timeout after " + count + " packets");
        }
        socket.close();
        System.out.printf("Result: received %d packets%n", count);
        System.out.println(count > 0 ? "PASS — RTP packets arriving" : "FAIL — no RTP received");
    }
}
