#ifdef HAVE_PJMEDIA

#include <jni.h>
#include <stdio.h>
#include <string.h>
#include "../rtp/ice_transport.h"

static IceSession* g_ice_session = NULL;

JNIEXPORT jint JNICALL
Java_com_telcobright_sipphone_linux_media_NativeIceBridge_nativeGatherCandidates(
    JNIEnv* env, jobject thiz,
    jstring stunServer, jint stunPort,
    jstring turnServer, jint turnPort,
    jstring turnUsername, jstring turnPassword,
    jboolean turnEnabled, jint localRtpPort) {

    (void)thiz;
    if (g_ice_session) {
        ice_session_destroy(g_ice_session);
        g_ice_session = NULL;
    }

    const char* stun = (*env)->GetStringUTFChars(env, stunServer, NULL);
    const char* turn = turnServer ? (*env)->GetStringUTFChars(env, turnServer, NULL) : "";
    const char* tuser = turnUsername ? (*env)->GetStringUTFChars(env, turnUsername, NULL) : "";
    const char* tpass = turnPassword ? (*env)->GetStringUTFChars(env, turnPassword, NULL) : "";

    IceSessionConfig config = {
        .stun_server = stun,
        .stun_port = stunPort,
        .turn_server = turn,
        .turn_port = turnPort,
        .turn_username = tuser,
        .turn_password = tpass,
        .turn_enabled = turnEnabled,
        .local_rtp_port = localRtpPort,
    };

    g_ice_session = ice_session_create(&config);

    (*env)->ReleaseStringUTFChars(env, stunServer, stun);
    if (turnServer) (*env)->ReleaseStringUTFChars(env, turnServer, turn);
    if (turnUsername) (*env)->ReleaseStringUTFChars(env, turnUsername, tuser);
    if (turnPassword) (*env)->ReleaseStringUTFChars(env, turnPassword, tpass);

    return g_ice_session ? ice_session_get_candidate_count(g_ice_session) : -1;
}

JNIEXPORT jint JNICALL
Java_com_telcobright_sipphone_linux_media_NativeIceBridge_nativeGetCandidateCount(
    JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    return g_ice_session ? ice_session_get_candidate_count(g_ice_session) : 0;
}

JNIEXPORT jstring JNICALL
Java_com_telcobright_sipphone_linux_media_NativeIceBridge_nativeGetCandidate(
    JNIEnv* env, jobject thiz, jint index) {
    (void)thiz;
    if (!g_ice_session) return NULL;

    IceCandidate c;
    if (ice_session_get_candidate(g_ice_session, index, &c) != 0) return NULL;

    char buf[256];
    snprintf(buf, sizeof(buf), "%s|%d|%s|%d|%s|%d|%s|%s|%d",
             c.foundation, c.component, c.transport, c.priority,
             c.address, c.port, c.type, c.rel_addr, c.rel_port);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL
Java_com_telcobright_sipphone_linux_media_NativeIceBridge_nativeGetIceUfrag(
    JNIEnv* env, jobject thiz) {
    (void)thiz;
    if (!g_ice_session) return NULL;
    char ufrag[32];
    ice_session_get_credentials(g_ice_session, ufrag, sizeof(ufrag), NULL, 0);
    return (*env)->NewStringUTF(env, ufrag);
}

JNIEXPORT jstring JNICALL
Java_com_telcobright_sipphone_linux_media_NativeIceBridge_nativeGetIcePwd(
    JNIEnv* env, jobject thiz) {
    (void)thiz;
    if (!g_ice_session) return NULL;
    char pwd[64];
    ice_session_get_credentials(g_ice_session, NULL, 0, pwd, sizeof(pwd));
    return (*env)->NewStringUTF(env, pwd);
}

JNIEXPORT jstring JNICALL
Java_com_telcobright_sipphone_linux_media_NativeIceBridge_nativeGetPublicAddress(
    JNIEnv* env, jobject thiz) {
    (void)thiz;
    if (!g_ice_session) return NULL;
    char addr[64];
    int port;
    if (ice_session_get_public_addr(g_ice_session, addr, sizeof(addr), &port) == 0) {
        char buf[80];
        snprintf(buf, sizeof(buf), "%s:%d", addr, port);
        return (*env)->NewStringUTF(env, buf);
    }
    return NULL;
}

JNIEXPORT void JNICALL
Java_com_telcobright_sipphone_linux_media_NativeIceBridge_nativeDestroyIce(
    JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    if (g_ice_session) {
        ice_session_destroy(g_ice_session);
        g_ice_session = NULL;
    }
}

#endif /* HAVE_PJMEDIA */
