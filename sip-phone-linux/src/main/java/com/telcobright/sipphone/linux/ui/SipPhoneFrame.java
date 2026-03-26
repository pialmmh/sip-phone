package com.telcobright.sipphone.linux.ui;

import com.telcobright.sipphone.linux.media.AmrAudioEngine;
import com.telcobright.sipphone.linux.media.NativeMediaBridge;
import com.telcobright.sipphone.linux.media.PcmuAudioEngine;
import com.telcobright.sipphone.linux.settings.AppSettings;
import com.telcobright.sipphone.media.PcmuRtpSession;
import com.telcobright.sipphone.verto.SdpBuilder;
import com.telcobright.sipphone.verto.VertoClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;

/**
 * Swing UI for Linux SIP Phone.
 */
public class SipPhoneFrame extends JFrame implements VertoClient.VertoEventListener {

    private static final Logger log = LoggerFactory.getLogger(SipPhoneFrame.class);

    /* Settings */
    private AppSettings settings;

    /* Registration panel */
    private JTextField tfServerUrl;
    private JTextField tfUsername;
    private JPasswordField tfPassword;
    private JButton btnRegister;
    private JLabel lblRegStatus;

    /* Call panel */
    private JTextField tfPhoneNumber;
    private JComboBox<String> cbCodec;
    private JButton btnCall;
    private JButton btnHangup;
    private JToggleButton btnMute;
    private JLabel lblCallStatus;
    private JLabel lblDuration;

    /* Settings panel */
    private JButton btnSaveSettings;

    /* State */
    private VertoClient vertoClient;
    private PcmuRtpSession pcmuRtpSession;
    private PcmuAudioEngine pcmuAudioEngine;
    private NativeMediaBridge amrBridge;
    private AmrAudioEngine amrAudioEngine;
    private String activeCodec;   // "PCMU", "AMR-NB", or "AMR-WB"
    private String currentCallId;
    private String pendingSdp;  // SDP from verto.media, used when verto.answer arrives
    private int localRtpPort;
    private String localIp;
    private volatile boolean registered;
    private ScheduledExecutorService timerExecutor;
    private long callStartTime;

    /* Profile */
    private JComboBox<String> cbProfile;
    private String currentProfileName;

    public SipPhoneFrame() {
        super("SIP Phone");
        AppSettings.migrateIfNeeded();
        currentProfileName = AppSettings.getLastProfile();
        if (currentProfileName.isEmpty()) currentProfileName = "btcl";
        settings = AppSettings.loadProfile(currentProfileName);
        allocatePorts();
        localIp = detectLocalIp();
        initUi();
        loadSettingsToUi();
    }

    private void initUi() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(420, 480);
        setLocationRelativeTo(null);
        setResizable(false);

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(new EmptyBorder(10, 15, 10, 15));

        mainPanel.add(createRegistrationPanel());
        mainPanel.add(Box.createVerticalStrut(8));
        mainPanel.add(createCallPanel());
        mainPanel.add(Box.createVerticalStrut(8));
        mainPanel.add(createStatusPanel());

        setContentPane(mainPanel);

        /* Shutdown hook */
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                doHangup();
                if (vertoClient != null) vertoClient.disconnect();
            }
        });
    }

    private JPanel createRegistrationPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new TitledBorder("Registration"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        /* Profile selector */
        c.gridx = 0; c.gridy = 0; c.weightx = 0; c.gridwidth = 1;
        panel.add(new JLabel("Profile:"), c);
        c.gridx = 1; c.weightx = 1; c.gridwidth = 2;
        List<String> profiles = AppSettings.listProfiles();
        cbProfile = new JComboBox<>(profiles.toArray(new String[0]));
        cbProfile.setSelectedItem(currentProfileName);
        cbProfile.addActionListener(e -> switchProfile((String) cbProfile.getSelectedItem()));
        panel.add(cbProfile, c);

        /* Server URL */
        c.gridx = 0; c.gridy = 1; c.weightx = 0; c.gridwidth = 1;
        panel.add(new JLabel("Server:"), c);
        c.gridx = 1; c.weightx = 1; c.gridwidth = 2;
        tfServerUrl = new JTextField(20);
        panel.add(tfServerUrl, c);

        /* Username */
        c.gridx = 0; c.gridy = 2; c.weightx = 0; c.gridwidth = 1;
        panel.add(new JLabel("User:"), c);
        c.gridx = 1; c.weightx = 1;
        tfUsername = new JTextField(10);
        panel.add(tfUsername, c);

        /* Password */
        c.gridx = 0; c.gridy = 3; c.weightx = 0;
        panel.add(new JLabel("Pass:"), c);
        c.gridx = 1; c.weightx = 1;
        tfPassword = new JPasswordField(10);
        panel.add(tfPassword, c);

        /* Buttons row */
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        btnSaveSettings = new JButton("Save");
        btnSaveSettings.setToolTipText("Save credentials to disk");
        btnSaveSettings.addActionListener(e -> saveSettings());
        btnRow.add(btnSaveSettings);

        btnRegister = new JButton("Register");
        btnRegister.addActionListener(e -> doRegister());
        btnRow.add(btnRegister);

        c.gridx = 0; c.gridy = 4; c.gridwidth = 3; c.weightx = 1;
        panel.add(btnRow, c);

        /* Status */
        lblRegStatus = new JLabel("Not registered");
        lblRegStatus.setForeground(Color.GRAY);
        c.gridx = 0; c.gridy = 5; c.gridwidth = 3;
        panel.add(lblRegStatus, c);

        return panel;
    }

    private JPanel createCallPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new TitledBorder("Call"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        /* Phone number */
        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        panel.add(new JLabel("Number:"), c);
        c.gridx = 1; c.weightx = 1; c.gridwidth = 2;
        tfPhoneNumber = new JTextField(15);
        panel.add(tfPhoneNumber, c);

        /* Codec */
        c.gridx = 0; c.gridy = 1; c.weightx = 0; c.gridwidth = 1;
        panel.add(new JLabel("Codec:"), c);
        c.gridx = 1; c.weightx = 1; c.gridwidth = 2;
        cbCodec = new JComboBox<>(new String[]{"PCMU", "AMR-NB", "AMR-WB"});
        panel.add(cbCodec, c);

        /* Call buttons */
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));

        btnCall = new JButton("Call");
        btnCall.setBackground(new Color(76, 175, 80));
        btnCall.setForeground(Color.WHITE);
        btnCall.addActionListener(e -> doCall());
        btnRow.add(btnCall);

        btnHangup = new JButton("Hangup");
        btnHangup.setBackground(new Color(244, 67, 54));
        btnHangup.setForeground(Color.WHITE);
        btnHangup.setEnabled(false);
        btnHangup.addActionListener(e -> doHangup());
        btnRow.add(btnHangup);

        btnMute = new JToggleButton("Mute");
        btnMute.setEnabled(false);
        btnMute.addActionListener(e -> {
            boolean mute = btnMute.isSelected();
            if (pcmuAudioEngine != null) pcmuAudioEngine.setMuted(mute);
            if (amrAudioEngine != null) amrAudioEngine.setMuted(mute);
        });
        btnRow.add(btnMute);

        c.gridx = 0; c.gridy = 2; c.gridwidth = 3;
        panel.add(btnRow, c);

        return panel;
    }

    private JPanel createStatusPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new TitledBorder("Status"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        panel.add(new JLabel("State:"), c);
        c.gridx = 1; c.weightx = 1;
        lblCallStatus = new JLabel("Idle");
        lblCallStatus.setFont(lblCallStatus.getFont().deriveFont(Font.BOLD));
        panel.add(lblCallStatus, c);

        c.gridx = 0; c.gridy = 1; c.weightx = 0;
        panel.add(new JLabel("Duration:"), c);
        c.gridx = 1; c.weightx = 1;
        lblDuration = new JLabel("--:--");
        panel.add(lblDuration, c);

        c.gridx = 0; c.gridy = 2; c.gridwidth = 2;
        JLabel lblLocal = new JLabel("Local: " + localIp + ":" + localRtpPort);
        lblLocal.setForeground(Color.GRAY);
        lblLocal.setFont(lblLocal.getFont().deriveFont(10f));
        panel.add(lblLocal, c);

        return panel;
    }

    /* === Settings & Profiles === */

    private void switchProfile(String profileName) {
        if (profileName == null || profileName.equals(currentProfileName)) return;
        currentProfileName = profileName;
        settings = AppSettings.loadProfile(profileName);
        AppSettings.saveLastProfile(profileName);
        loadSettingsToUi();
        /* Disconnect old session when switching profiles */
        if (vertoClient != null) {
            vertoClient.disconnect();
            vertoClient = null;
            registered = false;
            lblRegStatus.setText("Not registered");
            lblRegStatus.setForeground(Color.GRAY);
            btnRegister.setText("Register");
        }
        setTitle("SIP Phone — " + profileName);
        log.info("Switched to profile: {}", profileName);
    }

    private void loadSettingsToUi() {
        tfServerUrl.setText(settings.getServerUrl());
        tfUsername.setText(settings.getUsername());
        tfPassword.setText(settings.getPassword());
        cbCodec.setSelectedItem(settings.getPreferredCodec());
        tfPhoneNumber.setText(settings.getLastDialedNumber());
        setTitle("SIP Phone — " + currentProfileName);
    }

    private void saveSettings() {
        settings.setServerUrl(tfServerUrl.getText().trim());
        settings.setUsername(tfUsername.getText().trim());
        settings.setPassword(new String(tfPassword.getPassword()));
        settings.setPreferredCodec((String) cbCodec.getSelectedItem());
        settings.saveProfile(currentProfileName);
        AppSettings.saveLastProfile(currentProfileName);
        lblRegStatus.setText("Settings saved");
        lblRegStatus.setForeground(Color.BLUE);
    }

    /* === Registration === */

    private void doRegister() {
        String server = tfServerUrl.getText().trim();
        String user = tfUsername.getText().trim();
        String pass = new String(tfPassword.getPassword());

        if (server.isEmpty() || user.isEmpty()) {
            lblRegStatus.setText("Fill server and username");
            lblRegStatus.setForeground(Color.RED);
            return;
        }

        if (vertoClient != null) vertoClient.disconnect();
        vertoClient = new VertoClient(server, user, pass, this);

        btnRegister.setEnabled(false);
        lblRegStatus.setText("Connecting...");
        lblRegStatus.setForeground(Color.ORANGE);
        vertoClient.connect();
    }

    /* === Call === */

    private void doCall() {
        if (!registered) {
            lblCallStatus.setText("Not registered!");
            lblCallStatus.setForeground(Color.RED);
            return;
        }

        String number = tfPhoneNumber.getText().trim();
        if (number.isEmpty()) return;

        String codec = (String) cbCodec.getSelectedItem();
        String sdpOffer = SdpBuilder.buildOffer(localIp, localRtpPort, codec);
        currentCallId = vertoClient.invite(number, sdpOffer);

        settings.setLastDialedNumber(number);
        settings.saveProfile(currentProfileName);

        setCallUiState(true);
        lblCallStatus.setText("Calling " + number + "...");
        lblCallStatus.setForeground(Color.ORANGE);
        log.info("Calling {} with codec={}, callId={}", number, codec, currentCallId);
    }

    private void doHangup() {
        if (currentCallId != null && vertoClient != null) {
            vertoClient.bye(currentCallId);
        }
        stopMedia();
        currentCallId = null;
        pendingSdp = null;
        setCallUiState(false);
        lblCallStatus.setText("Idle");
        lblCallStatus.setForeground(Color.BLACK);
        lblDuration.setText("--:--");
    }

    private void startMedia(String remoteSdp) {
        log.info("startMedia called, SDP length={}, SDP:\n{}",
                 remoteSdp != null ? remoteSdp.length() : 0, remoteSdp);
        SdpBuilder.SdpMediaInfo info = SdpBuilder.parseRemoteSdp(remoteSdp);
        if (info == null) {
            log.error("SDP parse FAILED for:\n{}", remoteSdp);
            lblCallStatus.setText("ERROR: Bad SDP");
            lblCallStatus.setForeground(Color.RED);
            return;
        }
        log.info("SDP parsed: codec={}, remote={}:{}", info.codecName(), info.remoteIp(), info.remoteRtpPort());

        String codec = info.codecName();
        log.info("Starting media: codec={}, remote={}:{}", codec, info.remoteIp(), info.remoteRtpPort());

        activeCodec = codec;
        if ("PCMU".equals(codec)) {
            startPcmuMedia(info);
        } else {
            startAmrMedia(info);
        }
    }

    private void startPcmuMedia(SdpBuilder.SdpMediaInfo info) {
        try {
            pcmuRtpSession = new PcmuRtpSession(info.remoteIp(), info.remoteRtpPort(), localRtpPort);
            pcmuAudioEngine = new PcmuAudioEngine(pcmuRtpSession);
            pcmuRtpSession.start();
            pcmuAudioEngine.start();

            lblCallStatus.setText("In Call (PCMU)");
            lblCallStatus.setForeground(new Color(76, 175, 80));
            startDurationTimer();

            log.info("PCMU media active: local:{} -> {}:{}", localRtpPort, info.remoteIp(), info.remoteRtpPort());
        } catch (Exception e) {
            lblCallStatus.setText("Audio error: " + e.getMessage());
            lblCallStatus.setForeground(Color.RED);
            log.error("Failed to start PCMU audio: {}", e.getMessage(), e);
        }
    }

    private void startAmrMedia(SdpBuilder.SdpMediaInfo info) {
        try {
            int codecType = info.codecType();   // 0=NB, 1=WB
            int sampleRate = (codecType == 1) ? 16000 : 8000;
            int initialMode = (codecType == 1) ? 8 : 7;  // WB 23.85k or NB 12.2k

            amrBridge = new NativeMediaBridge();
            boolean created = amrBridge.nativeCreateRtpSession(
                    info.remoteIp(), info.remoteRtpPort(), info.remoteRtcpPort(),
                    localRtpPort, localRtpPort + 1,
                    new Random().nextInt(), info.payloadType(),
                    codecType, initialMode, false, null);

            if (!created) {
                lblCallStatus.setText("ERROR: Native RTP session failed");
                lblCallStatus.setForeground(Color.RED);
                log.error("Failed to create native AMR RTP session");
                return;
            }

            amrAudioEngine = new AmrAudioEngine(amrBridge, sampleRate);
            amrAudioEngine.start();

            String codecLabel = (codecType == 1) ? "AMR-WB" : "AMR-NB";
            lblCallStatus.setText("In Call (" + codecLabel + ")");
            lblCallStatus.setForeground(new Color(76, 175, 80));
            startDurationTimer();

            log.info("{} media active: local:{} -> {}:{}", codecLabel, localRtpPort,
                     info.remoteIp(), info.remoteRtpPort());
        } catch (Exception e) {
            lblCallStatus.setText("Audio error: " + e.getMessage());
            lblCallStatus.setForeground(Color.RED);
            log.error("Failed to start AMR audio: {}", e.getMessage(), e);
        }
    }

    private void stopMedia() {
        stopDurationTimer();
        /* Stop PCMU */
        if (pcmuAudioEngine != null) { pcmuAudioEngine.stop(); pcmuAudioEngine = null; }
        if (pcmuRtpSession != null) { pcmuRtpSession.stop(); pcmuRtpSession = null; }
        /* Stop AMR */
        if (amrAudioEngine != null) { amrAudioEngine.stop(); amrAudioEngine = null; }
        if (amrBridge != null) { amrBridge.nativeDestroyRtpSession(); amrBridge = null; }
        activeCodec = null;
    }

    /* === UI State === */

    private void setCallUiState(boolean inCall) {
        SwingUtilities.invokeLater(() -> {
            btnCall.setEnabled(!inCall);
            btnHangup.setEnabled(inCall);
            btnMute.setEnabled(inCall);
            tfPhoneNumber.setEnabled(!inCall);
            cbCodec.setEnabled(!inCall);
            if (!inCall) btnMute.setSelected(false);
        });
    }

    private void startDurationTimer() {
        callStartTime = System.currentTimeMillis();
        timerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "duration-timer");
            t.setDaemon(true);
            return t;
        });
        timerExecutor.scheduleAtFixedRate(() -> {
            long elapsed = (System.currentTimeMillis() - callStartTime) / 1000;
            SwingUtilities.invokeLater(() ->
                lblDuration.setText(String.format("%02d:%02d", elapsed / 60, elapsed % 60))
            );
        }, 0, 1, TimeUnit.SECONDS);
    }

    private void stopDurationTimer() {
        if (timerExecutor != null) { timerExecutor.shutdownNow(); timerExecutor = null; }
    }

    /* === Verto Callbacks === */

    @Override public void onConnected() {
        SwingUtilities.invokeLater(() -> {
            lblRegStatus.setText("Connected, logging in...");
            lblRegStatus.setForeground(Color.ORANGE);
        });
    }

    @Override public void onDisconnected(String reason) {
        registered = false;
        SwingUtilities.invokeLater(() -> {
            lblRegStatus.setText("Disconnected: " + reason);
            lblRegStatus.setForeground(Color.RED);
            btnRegister.setEnabled(true);
        });
    }

    @Override public void onLoginSuccess(String sessionId) {
        registered = true;
        SwingUtilities.invokeLater(() -> {
            lblRegStatus.setText("Registered (" + tfUsername.getText() + ")");
            lblRegStatus.setForeground(new Color(76, 175, 80));
            btnRegister.setEnabled(true);
            btnRegister.setText("Re-register");
        });
    }

    @Override public void onLoginFailed(String error) {
        registered = false;
        SwingUtilities.invokeLater(() -> {
            lblRegStatus.setText("Login failed: " + error);
            lblRegStatus.setForeground(Color.RED);
            btnRegister.setEnabled(true);
        });
    }

    @Override public void onIncomingCall(String callId, String callerNumber, String sdp) {
        SwingUtilities.invokeLater(() -> {
            int result = JOptionPane.showConfirmDialog(this,
                    "Incoming call from " + callerNumber, "Incoming Call",
                    JOptionPane.YES_NO_OPTION);
            if (result == JOptionPane.YES_OPTION) {
                currentCallId = callId;
                String answerSdp = SdpBuilder.buildAnswer(localIp, localRtpPort, sdp,
                        (String) cbCodec.getSelectedItem());
                vertoClient.answer(callId, answerSdp);
                setCallUiState(true);
                startMedia(sdp);
            } else {
                vertoClient.bye(callId);
            }
        });
    }

    @Override public void onCallAnswered(String callId, String sdp) {
        /* Use SDP from this event, or fall back to SDP saved from verto.media */
        String mediaSdp = (sdp != null && !sdp.isEmpty()) ? sdp : pendingSdp;
        log.info("onCallAnswered: callId={}, sdpLen={}, usingPendingSdp={}",
                 callId, mediaSdp != null ? mediaSdp.length() : 0, mediaSdp == pendingSdp);

        if (mediaSdp != null && !mediaSdp.isEmpty()) {
            SwingUtilities.invokeLater(() -> startMedia(mediaSdp));
        } else {
            log.error("No SDP available for answered call {}", callId);
            SwingUtilities.invokeLater(() -> {
                lblCallStatus.setText("ERROR: No SDP");
                lblCallStatus.setForeground(Color.RED);
            });
        }
        pendingSdp = null;
    }

    @Override public void onCallEnded(String callId, String reason) {
        SwingUtilities.invokeLater(() -> {
            stopMedia();
            currentCallId = null;
            setCallUiState(false);
            lblCallStatus.setText("Ended: " + reason);
            lblCallStatus.setForeground(Color.GRAY);
            lblDuration.setText("--:--");
        });
    }

    @Override public void onMediaUpdate(String callId, String sdp) {
        log.info("onMediaUpdate: callId={}, sdpLen={}", callId, sdp != null ? sdp.length() : 0);
        boolean mediaActive = (pcmuAudioEngine != null) || (amrAudioEngine != null && amrAudioEngine.isRunning());
        if (mediaActive) {
            /* Mid-call media update (re-INVITE) — restart media */
            SwingUtilities.invokeLater(() -> {
                stopMedia();
                startMedia(sdp);
            });
        } else {
            /* Pre-answer: save SDP, start media when verto.answer arrives */
            pendingSdp = sdp;
            log.info("SDP saved, waiting for verto.answer to start media");
            SwingUtilities.invokeLater(() -> {
                lblCallStatus.setText("Ringing...");
                lblCallStatus.setForeground(Color.ORANGE);
            });
        }
    }

    @Override public void onError(String error) {
        SwingUtilities.invokeLater(() ->
            JOptionPane.showMessageDialog(this, error, "Error", JOptionPane.ERROR_MESSAGE)
        );
    }

    /* === Utility === */

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
        } catch (Exception e) { return "0.0.0.0"; }
    }
}
