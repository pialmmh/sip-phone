package com.telcobright.sipphone.linux.media;

/**
 * JNI bridge for ICE/STUN/TURN candidate gathering.
 */
public class NativeIceBridge {

    static {
        System.loadLibrary("sipphone-native");
    }

    /**
     * Gather ICE candidates using STUN/TURN.
     * @return number of candidates gathered, or -1 on error
     */
    public native int nativeGatherCandidates(
            String stunServer, int stunPort,
            String turnServer, int turnPort,
            String turnUsername, String turnPassword,
            boolean turnEnabled,
            int localRtpPort);

    /** Get candidate count from last gathering. */
    public native int nativeGetCandidateCount();

    /** Get candidate info: returns "foundation|component|transport|priority|address|port|type|relAddr|relPort" */
    public native String nativeGetCandidate(int index);

    /** Get ICE ufrag. */
    public native String nativeGetIceUfrag();

    /** Get ICE pwd. */
    public native String nativeGetIcePwd();

    /** Get STUN-discovered public address. Returns "ip:port" or null. */
    public native String nativeGetPublicAddress();

    /** Destroy ICE session. */
    public native void nativeDestroyIce();
}
