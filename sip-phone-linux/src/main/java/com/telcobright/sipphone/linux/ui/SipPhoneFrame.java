package com.telcobright.sipphone.linux.ui;

import com.telcobright.sipphone.linux.media.LinuxMediaHandler;
import com.telcobright.sipphone.linux.settings.AppSettings;
import com.telcobright.sipphone.phone.PhoneCommand;
import com.telcobright.sipphone.phone.PhoneController;
import com.telcobright.sipphone.phone.PhoneState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.List;
import java.util.concurrent.*;

/**
 * Swing UI — fully decoupled.
 * Only sends PhoneCommand to PhoneController.
 * Only reads PhoneState for display.
 */
public class SipPhoneFrame extends JFrame {

    private static final Logger log = LoggerFactory.getLogger(SipPhoneFrame.class);

    private final PhoneController phone;
    private AppSettings settings;
    private String currentProfileName;

    /* Registration */
    private JComboBox<String> cbProfile;
    private JTextField tfServerUrl, tfUsername;
    private JPasswordField tfPassword;
    private JButton btnRegister, btnSaveSettings;
    private JLabel lblRegStatus;

    /* Call */
    private JTextField tfPhoneNumber;
    private JComboBox<String> cbCodec;
    private JButton btnCall, btnHangup;
    private JToggleButton btnMute;
    private JLabel lblCallStatus, lblDuration;

    private ScheduledExecutorService timerExecutor;
    private long callStartTime;

    public SipPhoneFrame() {
        super("SIP Phone");

        /* Core */
        phone = new PhoneController();
        phone.setMediaHandler(new LinuxMediaHandler());
        phone.addStateListener(this::onPhoneStateChanged);

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
                phone.execute(new PhoneCommand.Disconnect());
            }
        });
    }

    private JPanel createRegistrationPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new TitledBorder("Registration"));
        GridBagConstraints c = gbc();

        /* Profile */
        c.gridx=0; c.gridy=0; c.weightx=0; c.gridwidth=1;
        p.add(new JLabel("Profile:"), c);
        c.gridx=1; c.weightx=1; c.gridwidth=2;
        List<String> profiles = AppSettings.listProfiles();
        cbProfile = new JComboBox<>(profiles.toArray(new String[0]));
        cbProfile.setSelectedItem(currentProfileName);
        cbProfile.addActionListener(e -> switchProfile((String) cbProfile.getSelectedItem()));
        p.add(cbProfile, c);

        /* Server */
        c.gridx=0; c.gridy=1; c.weightx=0; c.gridwidth=1;
        p.add(new JLabel("Server:"), c);
        c.gridx=1; c.weightx=1; c.gridwidth=2;
        tfServerUrl = new JTextField(20);
        p.add(tfServerUrl, c);

        /* User / Pass */
        c.gridx=0; c.gridy=2; c.weightx=0; c.gridwidth=1;
        p.add(new JLabel("User:"), c);
        c.gridx=1; c.weightx=1;
        tfUsername = new JTextField(10);
        p.add(tfUsername, c);

        c.gridx=0; c.gridy=3; c.weightx=0;
        p.add(new JLabel("Pass:"), c);
        c.gridx=1; c.weightx=1;
        tfPassword = new JPasswordField(10);
        p.add(tfPassword, c);

        /* Buttons */
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        btnSaveSettings = new JButton("Save");
        btnSaveSettings.addActionListener(e -> saveSettings());
        row.add(btnSaveSettings);
        btnRegister = new JButton("Register");
        btnRegister.addActionListener(e -> {
            phone.execute(new PhoneCommand.Register(
                    tfServerUrl.getText().trim(),
                    tfUsername.getText().trim(),
                    new String(tfPassword.getPassword()),
                    "VERTO"));
        });
        row.add(btnRegister);
        c.gridx=0; c.gridy=4; c.gridwidth=3; c.weightx=1;
        p.add(row, c);

        /* Status */
        lblRegStatus = new JLabel("Not registered");
        lblRegStatus.setForeground(Color.GRAY);
        c.gridx=0; c.gridy=5; c.gridwidth=3;
        p.add(lblRegStatus, c);

        return p;
    }

    private JPanel createCallPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new TitledBorder("Call"));
        GridBagConstraints c = gbc();

        c.gridx=0; c.gridy=0; c.weightx=0; c.gridwidth=1;
        p.add(new JLabel("Number:"), c);
        c.gridx=1; c.weightx=1; c.gridwidth=2;
        tfPhoneNumber = new JTextField(15);
        p.add(tfPhoneNumber, c);

        c.gridx=0; c.gridy=1; c.weightx=0; c.gridwidth=1;
        p.add(new JLabel("Codec:"), c);
        c.gridx=1; c.weightx=1; c.gridwidth=2;
        cbCodec = new JComboBox<>(new String[]{"PCMU", "AMR-NB", "AMR-WB"});
        p.add(cbCodec, c);

        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        btnCall = new JButton("Call");
        btnCall.setBackground(new Color(76, 175, 80));
        btnCall.setForeground(Color.WHITE);
        btnCall.addActionListener(e -> {
            String num = tfPhoneNumber.getText().trim();
            if (!num.isEmpty()) {
                settings.setLastDialedNumber(num);
                settings.saveProfile(currentProfileName);
                phone.execute(new PhoneCommand.Dial(num, (String) cbCodec.getSelectedItem()));
            }
        });
        row.add(btnCall);

        btnHangup = new JButton("Hangup");
        btnHangup.setBackground(new Color(244, 67, 54));
        btnHangup.setForeground(Color.WHITE);
        btnHangup.setEnabled(false);
        btnHangup.addActionListener(e -> phone.execute(new PhoneCommand.Hangup()));
        row.add(btnHangup);

        btnMute = new JToggleButton("Mute");
        btnMute.setEnabled(false);
        btnMute.addActionListener(e -> phone.execute(new PhoneCommand.SetMute(btnMute.isSelected())));
        row.add(btnMute);

        c.gridx=0; c.gridy=2; c.gridwidth=3;
        p.add(row, c);

        return p;
    }

    private JPanel createStatusPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new TitledBorder("Status"));
        GridBagConstraints c = gbc();

        c.gridx=0; c.gridy=0; c.weightx=0;
        p.add(new JLabel("State:"), c);
        c.gridx=1; c.weightx=1;
        lblCallStatus = new JLabel("Idle");
        lblCallStatus.setFont(lblCallStatus.getFont().deriveFont(Font.BOLD));
        p.add(lblCallStatus, c);

        c.gridx=0; c.gridy=1; c.weightx=0;
        p.add(new JLabel("Duration:"), c);
        c.gridx=1; c.weightx=1;
        lblDuration = new JLabel("--:--");
        p.add(lblDuration, c);

        c.gridx=0; c.gridy=2; c.gridwidth=2;
        JLabel lbl = new JLabel("Local: " + phone.getLocalIp() + ":" + phone.getLocalRtpPort());
        lbl.setForeground(Color.GRAY);
        lbl.setFont(lbl.getFont().deriveFont(10f));
        p.add(lbl, c);

        return p;
    }

    /* === State change handler — UI updates here only === */

    private void onPhoneStateChanged(PhoneState s) {
        SwingUtilities.invokeLater(() -> {
            /* Registration */
            switch (s.getRegistration()) {
                case DISCONNECTED -> { lblRegStatus.setText(s.getRegistrationMessage()); lblRegStatus.setForeground(Color.RED); btnRegister.setEnabled(true); btnRegister.setText("Register"); }
                case CONNECTING -> { lblRegStatus.setText("Connecting..."); lblRegStatus.setForeground(Color.ORANGE); btnRegister.setEnabled(false); }
                case REGISTERED -> { lblRegStatus.setText("Registered"); lblRegStatus.setForeground(new Color(76,175,80)); btnRegister.setEnabled(true); btnRegister.setText("Re-register"); }
                case FAILED -> { lblRegStatus.setText(s.getRegistrationMessage()); lblRegStatus.setForeground(Color.RED); btnRegister.setEnabled(true); }
            }

            /* Call */
            switch (s.getCall()) {
                case IDLE -> { setCallUi(false); lblCallStatus.setText("Idle"); lblCallStatus.setForeground(Color.BLACK); stopDurationTimer(); lblDuration.setText("--:--"); }
                case TRYING -> { setCallUi(true); lblCallStatus.setText("Calling " + s.getRemoteNumber() + "..."); lblCallStatus.setForeground(Color.ORANGE); }
                case RINGING -> { lblCallStatus.setText("Ringing..."); lblCallStatus.setForeground(Color.ORANGE); }
                case INCOMING -> {
                    int r = JOptionPane.showConfirmDialog(this, "Incoming call from " + s.getRemoteNumber(), "Incoming", JOptionPane.YES_NO_OPTION);
                    if (r == JOptionPane.YES_OPTION) {
                        phone.execute(new PhoneCommand.Answer(s.getCallId()));
                    } else {
                        phone.execute(new PhoneCommand.Reject(s.getCallId()));
                    }
                }
                case ANSWERED -> { setCallUi(true); lblCallStatus.setText("In Call (" + s.getCodec() + ")"); lblCallStatus.setForeground(new Color(76,175,80)); startDurationTimer(); }
                case ENDED -> { setCallUi(false); lblCallStatus.setText("Ended: " + s.getEndReason()); lblCallStatus.setForeground(Color.GRAY); stopDurationTimer(); }
            }
        });
    }

    /* === UI helpers === */

    private void setCallUi(boolean inCall) {
        btnCall.setEnabled(!inCall);
        btnHangup.setEnabled(inCall);
        btnMute.setEnabled(inCall);
        tfPhoneNumber.setEnabled(!inCall);
        cbCodec.setEnabled(!inCall);
        if (!inCall) btnMute.setSelected(false);
    }

    private void startDurationTimer() {
        callStartTime = System.currentTimeMillis();
        timerExecutor = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "timer"); t.setDaemon(true); return t; });
        timerExecutor.scheduleAtFixedRate(() -> {
            long e = (System.currentTimeMillis() - callStartTime) / 1000;
            SwingUtilities.invokeLater(() -> lblDuration.setText(String.format("%02d:%02d", e/60, e%60)));
        }, 0, 1, TimeUnit.SECONDS);
    }

    private void stopDurationTimer() {
        if (timerExecutor != null) { timerExecutor.shutdownNow(); timerExecutor = null; }
    }

    private void switchProfile(String name) {
        if (name == null || name.equals(currentProfileName)) return;
        currentProfileName = name;
        settings = AppSettings.loadProfile(name);
        AppSettings.saveLastProfile(name);
        loadSettingsToUi();
        phone.execute(new PhoneCommand.Disconnect());
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
        settings.setPreferredCodec((String) cbCodec.getSelectedItem());
        settings.saveProfile(currentProfileName);
        AppSettings.saveLastProfile(currentProfileName);
        lblRegStatus.setText("Settings saved"); lblRegStatus.setForeground(Color.BLUE);
    }

    private static GridBagConstraints gbc() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        return c;
    }
}
