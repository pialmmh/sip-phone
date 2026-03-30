package com.telcobright.sipphone.media;

/**
 * ICE/STUN/TURN and IP binding configuration.
 * Portable across all platforms.
 */
public class IceConfig {

    public static final String DEFAULT_STUN_SERVER = "stun.l.google.com";
    public static final int DEFAULT_STUN_PORT = 19302;

    /* ICE */
    private boolean iceEnabled = false;
    private int gatherIntervalMs = 1000;      /* Re-gather every N ms when enabled */
    private String stunServer = DEFAULT_STUN_SERVER;
    private int stunPort = DEFAULT_STUN_PORT;

    /* TURN */
    private String turnServer = "";
    private int turnPort = 3478;
    private String turnUsername = "";
    private String turnPassword = "";
    private boolean turnEnabled = false;

    /* IP binding */
    public enum BindMode {
        AUTO,       /* Auto-select best IP (default route) */
        SPECIFIC    /* Bind to a specific IP */
    }
    private BindMode bindMode = BindMode.AUTO;
    private String bindAddress = "";          /* Used when bindMode=SPECIFIC */

    /* === ICE === */

    public boolean isIceEnabled() { return iceEnabled; }
    public void setIceEnabled(boolean v) { this.iceEnabled = v; }

    public int getGatherIntervalMs() { return gatherIntervalMs; }
    public void setGatherIntervalMs(int v) { this.gatherIntervalMs = v; }

    public String getStunServer() { return stunServer; }
    public void setStunServer(String v) { this.stunServer = v; }

    public int getStunPort() { return stunPort; }
    public void setStunPort(int v) { this.stunPort = v; }

    public String getStunAddress() { return stunServer + ":" + stunPort; }

    /* === TURN === */

    public String getTurnServer() { return turnServer; }
    public void setTurnServer(String v) { this.turnServer = v; }

    public int getTurnPort() { return turnPort; }
    public void setTurnPort(int v) { this.turnPort = v; }

    public String getTurnUsername() { return turnUsername; }
    public void setTurnUsername(String v) { this.turnUsername = v; }

    public String getTurnPassword() { return turnPassword; }
    public void setTurnPassword(String v) { this.turnPassword = v; }

    public boolean isTurnEnabled() { return turnEnabled; }
    public void setTurnEnabled(boolean v) { this.turnEnabled = v; }

    /* === IP Binding === */

    public BindMode getBindMode() { return bindMode; }
    public void setBindMode(BindMode v) { this.bindMode = v; }

    public String getBindAddress() { return bindAddress; }
    public void setBindAddress(String v) { this.bindAddress = v; }

    /* === Presets === */

    /** Default: ICE off, auto bind (current behavior) */
    public static IceConfig defaults() {
        return new IceConfig();
    }

    /** ICE enabled with Google STUN, auto bind */
    public static IceConfig withStun() {
        IceConfig c = new IceConfig();
        c.setIceEnabled(true);
        return c;
    }

    /** Specific IP, no ICE */
    public static IceConfig withBindAddress(String address) {
        IceConfig c = new IceConfig();
        c.setBindMode(BindMode.SPECIFIC);
        c.setBindAddress(address);
        return c;
    }
}
