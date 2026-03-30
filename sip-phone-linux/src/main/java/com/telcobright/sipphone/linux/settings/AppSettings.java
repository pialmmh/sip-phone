package com.telcobright.sipphone.linux.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Persisted application settings with multi-profile support.
 *
 * Profiles stored as: ~/.sipphone/profiles/btcl.json, ccl.json, etc.
 * Last selected profile stored in: ~/.sipphone/settings.json
 */
public class AppSettings {

    private static final Logger log = LoggerFactory.getLogger(AppSettings.class);
    private static final Path SETTINGS_DIR = Path.of(System.getProperty("user.home"), ".sipphone");
    private static final Path PROFILES_DIR = SETTINGS_DIR.resolve("profiles");
    private static final Path META_FILE = SETTINGS_DIR.resolve("settings.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /* Profile data */
    private String serverUrl = "";
    private String username = "";
    private String password = "";
    private String preferredCodec = "PCMU";
    private String lastDialedNumber = "";

    /* ICE/Network */
    private boolean iceEnabled = false;
    private String stunServer = "stun.l.google.com";
    private int stunPort = 19302;
    private boolean turnEnabled = false;
    private String turnServer = "";
    private int turnPort = 3478;
    private String turnUsername = "";
    private String turnPassword = "";
    private String bindMode = "AUTO";       /* AUTO or SPECIFIC */
    private String bindAddress = "";

    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String v) { this.serverUrl = v; }

    public String getUsername() { return username; }
    public void setUsername(String v) { this.username = v; }

    public String getPassword() { return password; }
    public void setPassword(String v) { this.password = v; }

    public String getPreferredCodec() { return preferredCodec; }
    public void setPreferredCodec(String v) { this.preferredCodec = v; }

    public String getLastDialedNumber() { return lastDialedNumber; }
    public void setLastDialedNumber(String v) { this.lastDialedNumber = v; }

    /* ICE/Network getters/setters */
    public boolean isIceEnabled() { return iceEnabled; }
    public void setIceEnabled(boolean v) { this.iceEnabled = v; }

    public String getStunServer() { return stunServer; }
    public void setStunServer(String v) { this.stunServer = v; }

    public int getStunPort() { return stunPort; }
    public void setStunPort(int v) { this.stunPort = v; }

    public boolean isTurnEnabled() { return turnEnabled; }
    public void setTurnEnabled(boolean v) { this.turnEnabled = v; }

    public String getTurnServer() { return turnServer; }
    public void setTurnServer(String v) { this.turnServer = v; }

    public int getTurnPort() { return turnPort; }
    public void setTurnPort(int v) { this.turnPort = v; }

    public String getTurnUsername() { return turnUsername; }
    public void setTurnUsername(String v) { this.turnUsername = v; }

    public String getTurnPassword() { return turnPassword; }
    public void setTurnPassword(String v) { this.turnPassword = v; }

    public String getBindMode() { return bindMode; }
    public void setBindMode(String v) { this.bindMode = v; }

    public String getBindAddress() { return bindAddress; }
    public void setBindAddress(String v) { this.bindAddress = v; }

    /** Build IceConfig from settings */
    public com.telcobright.sipphone.media.IceConfig toIceConfig() {
        var c = new com.telcobright.sipphone.media.IceConfig();
        c.setIceEnabled(iceEnabled);
        c.setStunServer(stunServer);
        c.setStunPort(stunPort);
        c.setTurnEnabled(turnEnabled);
        c.setTurnServer(turnServer);
        c.setTurnPort(turnPort);
        c.setTurnUsername(turnUsername);
        c.setTurnPassword(turnPassword);
        c.setBindMode("SPECIFIC".equals(bindMode)
                ? com.telcobright.sipphone.media.IceConfig.BindMode.SPECIFIC
                : com.telcobright.sipphone.media.IceConfig.BindMode.AUTO);
        c.setBindAddress(bindAddress);
        return c;
    }

    /* === Profile I/O === */

    /**
     * Save this settings as a named profile.
     */
    public void saveProfile(String profileName) {
        try {
            Files.createDirectories(PROFILES_DIR);
            Path file = PROFILES_DIR.resolve(profileName + ".json");
            Files.writeString(file, GSON.toJson(this));
            log.info("Profile saved: {}", file);
        } catch (IOException e) {
            log.error("Failed to save profile {}: {}", profileName, e.getMessage());
        }
    }

    /**
     * Load a named profile.
     */
    public static AppSettings loadProfile(String profileName) {
        Path file = PROFILES_DIR.resolve(profileName + ".json");
        if (Files.exists(file)) {
            try {
                String json = Files.readString(file);
                AppSettings s = GSON.fromJson(json, AppSettings.class);
                log.info("Profile loaded: {}", profileName);
                return s;
            } catch (Exception e) {
                log.error("Failed to load profile {}: {}", profileName, e.getMessage());
            }
        }
        return new AppSettings();
    }

    /**
     * List available profile names (without .json extension).
     */
    public static List<String> listProfiles() {
        List<String> profiles = new ArrayList<>();
        if (Files.exists(PROFILES_DIR)) {
            try (Stream<Path> files = Files.list(PROFILES_DIR)) {
                files.filter(p -> p.toString().endsWith(".json"))
                     .map(p -> p.getFileName().toString().replace(".json", ""))
                     .sorted()
                     .forEach(profiles::add);
            } catch (IOException e) {
                log.error("Failed to list profiles: {}", e.getMessage());
            }
        }
        return profiles;
    }

    /* === Meta (last selected profile) === */

    /**
     * Save which profile was last selected.
     */
    public static void saveLastProfile(String profileName) {
        try {
            Files.createDirectories(SETTINGS_DIR);
            Files.writeString(META_FILE, GSON.toJson(new Meta(profileName)));
        } catch (IOException e) {
            log.error("Failed to save meta: {}", e.getMessage());
        }
    }

    /**
     * Get the last selected profile name.
     */
    public static String getLastProfile() {
        if (Files.exists(META_FILE)) {
            try {
                Meta meta = GSON.fromJson(Files.readString(META_FILE), Meta.class);
                return meta != null && meta.lastProfile != null ? meta.lastProfile : "";
            } catch (Exception e) { /* ignore */ }
        }
        return "";
    }

    /**
     * Migrate old single settings.json to profile if needed.
     */
    public static void migrateIfNeeded() {
        if (Files.exists(META_FILE) && !Files.exists(PROFILES_DIR)) {
            try {
                String json = Files.readString(META_FILE);
                if (json.contains("serverUrl")) {
                    // Old format — migrate to "default" profile
                    Files.createDirectories(PROFILES_DIR);
                    Files.writeString(PROFILES_DIR.resolve("default.json"), json);
                    Files.writeString(META_FILE, GSON.toJson(new Meta("default")));
                    log.info("Migrated old settings to 'default' profile");
                }
            } catch (Exception e) {
                log.error("Migration failed: {}", e.getMessage());
            }
        }
    }

    private static class Meta {
        String lastProfile;
        Meta() {}
        Meta(String lastProfile) { this.lastProfile = lastProfile; }
    }
}
