package com.telcobright.sipphone.media;

/**
 * ICE/STUN/TURN configuration.
 * Portable across all platforms.
 */
public class IceConfig {

    public static final String DEFAULT_STUN_SERVER = "stun.l.google.com";
    public static final int DEFAULT_STUN_PORT = 19302;

    private boolean iceEnabled = true;
    private String stunServer = DEFAULT_STUN_SERVER;
    private int stunPort = DEFAULT_STUN_PORT;
    private String turnServer = "";
    private int turnPort = 3478;
    private String turnUsername = "";
    private String turnPassword = "";
    private boolean turnEnabled = false;

    public boolean isIceEnabled() { return iceEnabled; }
    public void setIceEnabled(boolean v) { this.iceEnabled = v; }

    public String getStunServer() { return stunServer; }
    public void setStunServer(String v) { this.stunServer = v; }

    public int getStunPort() { return stunPort; }
    public void setStunPort(int v) { this.stunPort = v; }

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

    /** STUN server as "host:port" */
    public String getStunAddress() {
        return stunServer + ":" + stunPort;
    }

    /** Default config with Google STUN */
    public static IceConfig defaults() {
        return new IceConfig();
    }

    /** Disabled — no ICE, direct RTP (current behavior) */
    public static IceConfig disabled() {
        IceConfig c = new IceConfig();
        c.setIceEnabled(false);
        return c;
    }
}
