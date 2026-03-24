/*
 * JNI bridge for Linux — exposes AMR codec, RTP session, and media engine to Java.
 *
 * Provides the same JNI functions as the Android version but without Oboe audio.
 * Audio I/O is handled in Java via javax.sound.sampled.
 */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../rtp/rtp_session.h"

static RtpSession *g_rtp_session = NULL;

/* Cached JVM and callback references */
static JavaVM *g_jvm = NULL;
static jobject g_quality_listener = NULL;
static jmethodID g_on_quality_update = NULL;

static void quality_callback(const RtcpQualityMetrics *metrics, void *user_data) {
    (void)user_data;
    if (!g_jvm || !g_quality_listener) return;

    JNIEnv *env;
    int attached = 0;
    if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) {
        (*g_jvm)->AttachCurrentThread(g_jvm, (void **)&env, NULL);
        attached = 1;
    }

    if (env && g_on_quality_update) {
        (*env)->CallVoidMethod(env, g_quality_listener, g_on_quality_update,
                               (jfloat)metrics->packet_loss_percent,
                               (jfloat)metrics->jitter_ms,
                               (jfloat)metrics->rtt_ms);
    }

    if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

/*
 * Class: com.telcobright.sipphone.linux.media.NativeMediaBridge
 */

JNIEXPORT jboolean JNICALL
Java_com_telcobright_sipphone_linux_media_NativeMediaBridge_nativeCreateRtpSession(
    JNIEnv *env, jobject thiz,
    jstring remoteHost, jint remoteRtpPort, jint remoteRtcpPort,
    jint localRtpPort, jint localRtcpPort,
    jint ssrc, jint payloadType,
    jint codecType, jint initialMode, jboolean dtx,
    jobject qualityListener) {

    (void)thiz;
    if (g_rtp_session) {
        fprintf(stderr, "RTP session already active\n");
        return JNI_FALSE;
    }

    if (qualityListener) {
        g_quality_listener = (*env)->NewGlobalRef(env, qualityListener);
        jclass cls = (*env)->GetObjectClass(env, qualityListener);
        g_on_quality_update = (*env)->GetMethodID(env, cls, "onQualityUpdate", "(FFF)V");
    }

    const char *host = (*env)->GetStringUTFChars(env, remoteHost, NULL);

    RtpSessionConfig config = {0};
    config.remote_host = host;
    config.remote_rtp_port = remoteRtpPort;
    config.remote_rtcp_port = remoteRtcpPort;
    config.local_rtp_port = localRtpPort;
    config.local_rtcp_port = localRtcpPort;
    config.ssrc = (uint32_t)ssrc;
    config.payload_type = (uint8_t)payloadType;
    config.codec_type = (AmrCodecType)codecType;
    config.initial_mode = initialMode;
    config.dtx = dtx ? 1 : 0;
    config.quality_callback = quality_callback;
    config.quality_user_data = NULL;

    g_rtp_session = rtp_session_create(&config);
    (*env)->ReleaseStringUTFChars(env, remoteHost, host);

    return g_rtp_session ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_telcobright_sipphone_linux_media_NativeMediaBridge_nativeDestroyRtpSession(
    JNIEnv *env, jobject thiz) {
    (void)thiz;
    if (g_rtp_session) {
        rtp_session_destroy(g_rtp_session);
        g_rtp_session = NULL;
    }
    if (g_quality_listener) {
        (*env)->DeleteGlobalRef(env, g_quality_listener);
        g_quality_listener = NULL;
    }
}

JNIEXPORT jint JNICALL
Java_com_telcobright_sipphone_linux_media_NativeMediaBridge_nativeSendFrame(
    JNIEnv *env, jobject thiz, jshortArray pcmData, jint cmr) {
    (void)thiz;
    if (!g_rtp_session) return -1;

    jshort *pcm = (*env)->GetShortArrayElements(env, pcmData, NULL);
    int ret = rtp_session_send_frame(g_rtp_session, pcm, cmr);
    (*env)->ReleaseShortArrayElements(env, pcmData, pcm, JNI_ABORT);
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_telcobright_sipphone_linux_media_NativeMediaBridge_nativeReceiveFrame(
    JNIEnv *env, jobject thiz, jshortArray pcmOut, jintArray cmrOut) {
    (void)thiz;
    if (!g_rtp_session) return 0;

    jshort *pcm = (*env)->GetShortArrayElements(env, pcmOut, NULL);
    int cmr_received = -1;
    int samples = rtp_session_receive_frame(g_rtp_session, pcm, &cmr_received);
    (*env)->ReleaseShortArrayElements(env, pcmOut, pcm, 0);

    if (cmrOut) {
        jint cmr_val = cmr_received;
        (*env)->SetIntArrayRegion(env, cmrOut, 0, 1, &cmr_val);
    }

    /* Also process RTCP */
    rtp_session_process_rtcp(g_rtp_session);

    return samples;
}

JNIEXPORT void JNICALL
Java_com_telcobright_sipphone_linux_media_NativeMediaBridge_nativeSetMode(
    JNIEnv *env, jobject thiz, jint mode) {
    (void)env; (void)thiz;
    if (g_rtp_session) rtp_session_set_mode(g_rtp_session, mode);
}

JNIEXPORT void JNICALL
Java_com_telcobright_sipphone_linux_media_NativeMediaBridge_nativeSendRtcp(
    JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    if (g_rtp_session) rtp_session_send_rtcp(g_rtp_session);
}
