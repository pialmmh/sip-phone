package com.telcobright.sipphone.call;

/**
 * Volatile call context — runtime-only data.
 */
public class CallVolatileContext {

    private String serverUrl = "";
    private String username = "";
    private String password = "";

    public CallVolatileContext() {}

    public CallVolatileContext(String serverUrl, String username, String password) {
        this.serverUrl = serverUrl;
        this.username = username;
        this.password = password;
    }

    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
