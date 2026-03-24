package com.telcobright.sipphone.linux;

import com.telcobright.sipphone.call.CallData;
import com.telcobright.sipphone.call.CallVolatileContext;
import com.telcobright.sipphone.call.events.CallEvents.*;
import com.telcobright.sipphone.linux.media.JavaSoundAudioEngine;
import com.telcobright.sipphone.linux.media.NativeMediaBridge;
import com.telcobright.sipphone.statemachine.FluentBuilder;
import com.telcobright.sipphone.statemachine.GenericStateMachine;
import com.telcobright.sipphone.statemachine.StateMachineRegistry;
import com.telcobright.sipphone.verto.SdpBuilder;
import com.telcobright.sipphone.verto.VertoClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Linux SIP Phone — CLI application.
 *
 * Usage: java -Djava.library.path=native -jar sip-phone-linux.jar
 *
 * Commands:
 *   register <ws-url> <username> <password>
 *   call <number> [PCMU|AMR-NB|AMR-WB]
 *   hangup
 *   quit
 */
public class SipPhoneMain implements VertoClient.VertoEventListener {

    private static final Logger log = LoggerFactory.getLogger(SipPhoneMain.class);

    private VertoClient vertoClient;
    private final StateMachineRegistry registry = new StateMachineRegistry();
    private GenericStateMachine<CallData, CallVolatileContext> callMachine;
    private final NativeMediaBridge mediaBridge = new NativeMediaBridge();
    private JavaSoundAudioEngine audioEngine;

    private String currentCallId;
    private int localRtpPort;
    private String localIp;
    private final CountDownLatch loginLatch = new CountDownLatch(1);
    private volatile boolean loggedIn;

    public static void main(String[] args) throws Exception {
        new SipPhoneMain().run();
    }

    private void run() throws Exception {
        allocatePorts();
        localIp = detectLocalIp();

        System.out.println("=== SIP Phone (Linux) ===");
        System.out.println("Local IP: " + localIp + ", RTP port: " + localRtpPort);
        System.out.println("Commands: register <ws-url> <user> <pass> | call <number> [codec] | hangup | quit");
        System.out.println();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+");
            String cmd = parts[0].toLowerCase();

            switch (cmd) {
                case "register" -> {
                    if (parts.length < 4) {
                        System.out.println("Usage: register <ws-url> <username> <password>");
                        continue;
                    }
                    doRegister(parts[1], parts[2], parts[3]);
                }
                case "call" -> {
                    if (parts.length < 2) {
                        System.out.println("Usage: call <number> [PCMU|AMR-NB|AMR-WB]");
                        continue;
                    }
                    String codec = parts.length >= 3 ? parts[2] : "PCMU";
                    doCall(parts[1], codec);
                }
                case "hangup" -> doHangup();
                case "quit", "exit" -> {
                    doHangup();
                    if (vertoClient != null) vertoClient.disconnect();
                    registry.shutdown();
                    System.out.println("Bye.");
                    System.exit(0);
                }
                default -> System.out.println("Unknown command: " + cmd);
            }
        }
    }

    private void doRegister(String serverUrl, String username, String password) {
        if (vertoClient != null) vertoClient.disconnect();
        vertoClient = new VertoClient(serverUrl, username, password, this);
        System.out.println("Connecting to " + serverUrl + "...");
        vertoClient.connect();

        try {
            if (loginLatch.await(10, TimeUnit.SECONDS) && loggedIn) {
                System.out.println("Registered as " + username);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void doCall(String destination, String codec) {
        if (vertoClient == null || !loggedIn) {
            System.out.println("Not registered. Use: register <url> <user> <pass>");
            return;
        }

        String sdpOffer = SdpBuilder.buildOffer(localIp, localRtpPort, codec);
        currentCallId = vertoClient.invite(destination, sdpOffer);

        System.out.println("Calling " + destination + " (codec=" + codec + ", callId=" + currentCallId + ")...");
    }

    private void doHangup() {
        if (currentCallId != null && vertoClient != null) {
            vertoClient.bye(currentCallId);
            System.out.println("Hanging up...");
        }
        stopMedia();
        currentCallId = null;
    }

    private void startMedia(String remoteSdp) {
        SdpBuilder.SdpMediaInfo info = SdpBuilder.parseRemoteSdp(remoteSdp);
        if (info == null) {
            System.out.println("ERROR: Cannot parse remote SDP");
            return;
        }

        int sampleRate = 8000; // PCMU and AMR-NB
        int codecType = info.codecType();
        int initialMode = 7; // MR122 for NB

        if (info.codecName().equals(SdpBuilder.CODEC_AMR_WB)) {
            sampleRate = 16000;
            initialMode = 8; // WB 23.85k
        }

        boolean created = mediaBridge.nativeCreateRtpSession(
                info.remoteIp(), info.remoteRtpPort(), info.remoteRtcpPort(),
                localRtpPort, localRtpPort + 1,
                new Random().nextInt(), info.payloadType(),
                codecType, initialMode, false, null);

        if (!created) {
            System.out.println("ERROR: Failed to create RTP session");
            return;
        }

        try {
            audioEngine = new JavaSoundAudioEngine(mediaBridge, sampleRate);
            audioEngine.start();
            System.out.println("Media started: " + info.codecName() + " -> " +
                    info.remoteIp() + ":" + info.remoteRtpPort());
        } catch (Exception e) {
            System.out.println("ERROR: Audio failed: " + e.getMessage());
            mediaBridge.nativeDestroyRtpSession();
        }
    }

    private void stopMedia() {
        if (audioEngine != null) {
            audioEngine.stop();
            audioEngine = null;
        }
        mediaBridge.nativeDestroyRtpSession();
    }

    /* === VertoClient.VertoEventListener === */

    @Override public void onConnected() {
        System.out.println("[VERTO] Connected");
    }

    @Override public void onDisconnected(String reason) {
        System.out.println("[VERTO] Disconnected: " + reason);
        loggedIn = false;
    }

    @Override public void onLoginSuccess(String sessionId) {
        System.out.println("[VERTO] Login OK (session=" + sessionId + ")");
        loggedIn = true;
        loginLatch.countDown();
    }

    @Override public void onLoginFailed(String error) {
        System.out.println("[VERTO] Login FAILED: " + error);
        loggedIn = false;
        loginLatch.countDown();
    }

    @Override public void onIncomingCall(String callId, String callerNumber, String sdp) {
        System.out.println("[INCOMING] Call from " + callerNumber + " (callId=" + callId + ")");
        currentCallId = callId;
        /* Auto-answer */
        String answerSdp = SdpBuilder.buildAnswer(localIp, localRtpPort, sdp, SdpBuilder.CODEC_PCMU);
        vertoClient.answer(callId, answerSdp);
        startMedia(sdp);
    }

    @Override public void onCallAnswered(String callId, String sdp) {
        System.out.println("[ANSWERED] Call connected");
        startMedia(sdp);
    }

    @Override public void onCallEnded(String callId, String reason) {
        System.out.println("[ENDED] " + reason);
        stopMedia();
        currentCallId = null;
    }

    @Override public void onMediaUpdate(String callId, String sdp) {
        System.out.println("[MEDIA UPDATE]");
        stopMedia();
        startMedia(sdp);
    }

    @Override public void onError(String error) {
        System.out.println("[ERROR] " + error);
    }

    private void allocatePorts() {
        Random rng = new Random();
        for (int i = 0; i < 50; i++) {
            int port = 10000 + (rng.nextInt(10000) & 0xFFFE);
            try {
                new DatagramSocket(port).close();
                new DatagramSocket(port + 1).close();
                localRtpPort = port;
                return;
            } catch (Exception ignored) {}
        }
        localRtpPort = 10000;
    }

    private String detectLocalIp() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName("8.8.8.8"), 10002);
            return s.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            return "0.0.0.0";
        }
    }
}
