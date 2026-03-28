package com.telcobright.sipphone.linux.ui;

import com.telcobright.sipphone.linux.media.LinuxMediaHandler;
import com.telcobright.sipphone.linux.settings.AppSettings;
import com.telcobright.sipphone.phone.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.List;
import java.util.concurrent.*;

/**
 * Swing UI — pure rendering. Zero logic.
 * Fires UiAction (async), subscribes to UiViewModel.
 */
public class SipPhoneFrame extends JFrame {

    private static final Logger log = LoggerFactory.getLogger(SipPhoneFrame.class);

    private final PhoneEngine engine;
    private final UiStateMachine uiSm;
    private AppSettings settings;
    private String currentProfileName;

    /* Widgets */
    private JComboBox<String> cbProfile;
    private JTextField tfServerUrl, tfUsername;
    private JPasswordField tfPassword;
    private JButton btnRegister, btnSaveSettings;
    private JLabel lblRegStatus;
    private JTextField tfPhoneNumber;
    private JComboBox<String> cbCodec;
    private JButton btnCall, btnHangup;
    private JToggleButton btnMute;
    private JLabel lblCallStatus, lblDuration;

    private ScheduledExecutorService timerExecutor;
    private long callStartTime;

    public SipPhoneFrame() {
        super("SIP Phone");

        /* Engine */
        engine = new PhoneEngine();
        engine.setMediaHandler(new LinuxMediaHandler());
        engine.start();

        uiSm = engine.getUiStateMachine();

        /* Subscribe to UiViewModel — re-render on every change */
        engine.getBus().subscribe(UiViewModel.class, vm -> SwingUtilities.invokeLater(() -> render(vm)));

        /* Settings */
        AppSettings.migrateIfNeeded();
        currentProfileName = AppSettings.getLastProfile();
        if (currentProfileName.isEmpty()) currentProfileName = "btcl";
        settings = AppSettings.loadProfile(currentProfileName);

        initUi();
        loadSettingsToUi();
    }

    private void initUi() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(420, 500);
        setLocationRelativeTo(null);
        setResizable(false);

        JPanel main = new JPanel();
        main.setLayout(new BoxLayout(main, BoxLayout.Y_AXIS));
        main.setBorder(new EmptyBorder(10, 15, 10, 15));
        main.add(createRegistrationPanel());
        main.add(Box.createVerticalStrut(8));
        main.add(createCallPanel());
        main.add(Box.createVerticalStrut(8));
        main.add(createStatusPanel());
        setContentPane(main);

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                uiSm.handleAction(new UiAction.Disconnect());
                engine.shutdown();
            }
        });
    }

    /* === Panels === */

    private JPanel createRegistrationPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new TitledBorder("Registration"));
        GridBagConstraints c = gbc();

        c.gridx=0; c.gridy=0; c.weightx=0; c.gridwidth=1;
        p.add(new JLabel("Profile:"), c);
        c.gridx=1; c.weightx=1; c.gridwidth=2;
        cbProfile = new JComboBox<>(AppSettings.listProfiles().toArray(new String[0]));
        cbProfile.setSelectedItem(currentProfileName);
        cbProfile.addActionListener(e -> switchProfile((String)cbProfile.getSelectedItem()));
        p.add(cbProfile, c);

        c.gridx=0; c.gridy=1; c.weightx=0; c.gridwidth=1;
        p.add(new JLabel("Server:"), c);
        c.gridx=1; c.weightx=1; c.gridwidth=2;
        tfServerUrl = new JTextField(20); p.add(tfServerUrl, c);

        c.gridx=0; c.gridy=2; c.weightx=0; c.gridwidth=1;
        p.add(new JLabel("User:"), c);
        c.gridx=1; c.weightx=1; tfUsername = new JTextField(10); p.add(tfUsername, c);

        c.gridx=0; c.gridy=3; c.weightx=0;
        p.add(new JLabel("Pass:"), c);
        c.gridx=1; c.weightx=1; tfPassword = new JPasswordField(10); p.add(tfPassword, c);

        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        btnSaveSettings = new JButton("Save");
        btnSaveSettings.addActionListener(e -> saveSettings());
        row.add(btnSaveSettings);
        btnRegister = new JButton("Register");
        btnRegister.addActionListener(e -> uiSm.handleAction(new UiAction.Register(
                tfServerUrl.getText().trim(), tfUsername.getText().trim(),
                new String(tfPassword.getPassword()), "VERTO")));
        row.add(btnRegister);
        c.gridx=0; c.gridy=4; c.gridwidth=3; c.weightx=1; p.add(row, c);

        lblRegStatus = new JLabel("Not registered"); lblRegStatus.setForeground(Color.GRAY);
        c.gridx=0; c.gridy=5; c.gridwidth=3; p.add(lblRegStatus, c);
        return p;
    }

    private JPanel createCallPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new TitledBorder("Call"));
        GridBagConstraints c = gbc();

        c.gridx=0; c.gridy=0; c.weightx=0; c.gridwidth=1;
        p.add(new JLabel("Number:"), c);
        c.gridx=1; c.weightx=1; c.gridwidth=2;
        tfPhoneNumber = new JTextField(15); p.add(tfPhoneNumber, c);

        c.gridx=0; c.gridy=1; c.weightx=0; c.gridwidth=1;
        p.add(new JLabel("Codec:"), c);
        c.gridx=1; c.weightx=1; c.gridwidth=2;
        cbCodec = new JComboBox<>(new String[]{"PCMU", "AMR-NB", "AMR-WB"}); p.add(cbCodec, c);

        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        btnCall = new JButton("Call"); btnCall.setBackground(new Color(76,175,80)); btnCall.setForeground(Color.WHITE);
        btnCall.addActionListener(e -> {
            String num = tfPhoneNumber.getText().trim();
            if (!num.isEmpty()) {
                settings.setLastDialedNumber(num);
                settings.saveProfile(currentProfileName);
                uiSm.handleAction(new UiAction.Dial(num, (String)cbCodec.getSelectedItem()));
            }
        });
        row.add(btnCall);

        btnHangup = new JButton("Hangup"); btnHangup.setBackground(new Color(244,67,54)); btnHangup.setForeground(Color.WHITE);
        btnHangup.setEnabled(false);
        btnHangup.addActionListener(e -> uiSm.handleAction(new UiAction.Hangup()));
        row.add(btnHangup);

        btnMute = new JToggleButton("Mute"); btnMute.setEnabled(false);
        btnMute.addActionListener(e -> uiSm.handleAction(new UiAction.Mute(btnMute.isSelected())));
        row.add(btnMute);

        c.gridx=0; c.gridy=2; c.gridwidth=3; p.add(row, c);
        return p;
    }

    private JPanel createStatusPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new TitledBorder("Status"));
        GridBagConstraints c = gbc();

        c.gridx=0; c.gridy=0; c.weightx=0; p.add(new JLabel("State:"), c);
        c.gridx=1; c.weightx=1; lblCallStatus = new JLabel("Idle");
        lblCallStatus.setFont(lblCallStatus.getFont().deriveFont(Font.BOLD)); p.add(lblCallStatus, c);

        c.gridx=0; c.gridy=1; c.weightx=0; p.add(new JLabel("Duration:"), c);
        c.gridx=1; c.weightx=1; lblDuration = new JLabel("--:--"); p.add(lblDuration, c);

        c.gridx=0; c.gridy=2; c.gridwidth=2;
        JLabel lbl = new JLabel("Local: " + engine.getLocalIp() + ":" + engine.getLocalRtpPort());
        lbl.setForeground(Color.GRAY); lbl.setFont(lbl.getFont().deriveFont(10f)); p.add(lbl, c);
        return p;
    }

    /* === Render — called on every UiViewModel change === */

    private void render(UiViewModel vm) {
        /* Registration */
        switch (vm.regState()) {
            case DISCONNECTED -> { lblRegStatus.setText(vm.regMessage().isEmpty() ? "Not registered" : vm.regMessage()); lblRegStatus.setForeground(Color.RED); btnRegister.setEnabled(true); btnRegister.setText("Register"); }
            case CONNECTING -> { lblRegStatus.setText(vm.regMessage()); lblRegStatus.setForeground(Color.ORANGE); btnRegister.setEnabled(false); }
            case REGISTERED -> { lblRegStatus.setText("Registered"); lblRegStatus.setForeground(new Color(76,175,80)); btnRegister.setEnabled(true); btnRegister.setText("Re-register"); }
            case FAILED -> { lblRegStatus.setText(vm.regMessage()); lblRegStatus.setForeground(Color.RED); btnRegister.setEnabled(true); }
        }

        /* Call */
        boolean inCall = false;
        switch (vm.callState()) {
            case IDLE -> { lblCallStatus.setText("Idle"); lblCallStatus.setForeground(Color.BLACK); stopTimer(); lblDuration.setText("--:--"); }
            case DIALING -> { inCall=true; lblCallStatus.setText("Calling " + vm.remoteNumber() + "..."); lblCallStatus.setForeground(Color.ORANGE); }
            case RINGING -> { inCall=true; lblCallStatus.setText("Ringing..."); lblCallStatus.setForeground(Color.ORANGE); }
            case INCOMING -> {
                int r = JOptionPane.showConfirmDialog(this, "Incoming: " + vm.remoteNumber(), "Incoming", JOptionPane.YES_NO_OPTION);
                if (r == JOptionPane.YES_OPTION) uiSm.handleAction(new UiAction.Answer(vm.callId()));
                else uiSm.handleAction(new UiAction.Reject(vm.callId()));
            }
            case IN_CALL -> { inCall=true; lblCallStatus.setText("In Call (" + vm.codec() + ")"); lblCallStatus.setForeground(new Color(76,175,80)); startTimer(); }
            case ENDING -> { lblCallStatus.setText("Ended: " + vm.endReason()); lblCallStatus.setForeground(Color.GRAY); stopTimer(); }
        }

        btnCall.setEnabled(!inCall);
        btnHangup.setEnabled(inCall);
        btnMute.setEnabled(inCall);
        tfPhoneNumber.setEnabled(!inCall);
        cbCodec.setEnabled(!inCall);
        if (!inCall) btnMute.setSelected(false);
    }

    /* === Timer / Settings / Profile === */

    private void startTimer() {
        if (timerExecutor != null) return;
        callStartTime = System.currentTimeMillis();
        timerExecutor = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r,"timer"); t.setDaemon(true); return t; });
        timerExecutor.scheduleAtFixedRate(() -> {
            long e = (System.currentTimeMillis()-callStartTime)/1000;
            SwingUtilities.invokeLater(() -> lblDuration.setText(String.format("%02d:%02d", e/60, e%60)));
        }, 0, 1, TimeUnit.SECONDS);
    }

    private void stopTimer() {
        if (timerExecutor != null) { timerExecutor.shutdownNow(); timerExecutor = null; }
    }

    private void switchProfile(String name) {
        if (name == null || name.equals(currentProfileName)) return;
        currentProfileName = name;
        settings = AppSettings.loadProfile(name);
        AppSettings.saveLastProfile(name);
        loadSettingsToUi();
        uiSm.handleAction(new UiAction.Disconnect());
        setTitle("SIP Phone — " + name);
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
        settings.setPreferredCodec((String)cbCodec.getSelectedItem());
        settings.saveProfile(currentProfileName);
        AppSettings.saveLastProfile(currentProfileName);
        lblRegStatus.setText("Settings saved"); lblRegStatus.setForeground(Color.BLUE);
    }

    private static GridBagConstraints gbc() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2,4,2,4); c.fill = GridBagConstraints.HORIZONTAL; return c;
    }
}
