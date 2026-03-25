package com.telcobright.sipphone.linux.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted application settings — saved as JSON in ~/.sipphone/settings.json
 */
public class AppSettings {

    private static final Logger log = LoggerFactory.getLogger(AppSettings.class);
    private static final Path SETTINGS_DIR = Path.of(System.getProperty("user.home"), ".sipphone");
    private static final Path SETTINGS_FILE = SETTINGS_DIR.resolve("settings.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private String serverUrl = "";
    private String username = "";
    private String password = "";
    private String preferredCodec = "PCMU";
    private String lastDialedNumber = "";

    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getPreferredCodec() { return preferredCodec; }
    public void setPreferredCodec(String codec) { this.preferredCodec = codec; }

    public String getLastDialedNumber() { return lastDialedNumber; }
    public void setLastDialedNumber(String number) { this.lastDialedNumber = number; }

    public void save() {
        try {
            Files.createDirectories(SETTINGS_DIR);
            Files.writeString(SETTINGS_FILE, GSON.toJson(this));
            log.info("Settings saved to {}", SETTINGS_FILE);
        } catch (IOException e) {
            log.error("Failed to save settings: {}", e.getMessage());
        }
    }

    public static AppSettings load() {
        if (Files.exists(SETTINGS_FILE)) {
            try {
                String json = Files.readString(SETTINGS_FILE);
                AppSettings settings = GSON.fromJson(json, AppSettings.class);
                log.info("Settings loaded from {}", SETTINGS_FILE);
                return settings;
            } catch (Exception e) {
                log.error("Failed to load settings: {}", e.getMessage());
            }
        }
        return new AppSettings();
    }
}
