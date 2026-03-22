#include <jni.h>
#include <android/log.h>
#include <string>
#include <memory>
#include "../rtp/rtp_session.h"
#include "../audio/audio_engine.h"

#define LOG_TAG "JniBridge"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Global instances — one call at a time */
static RtpSession* g_rtp_session = nullptr;
static std::unique_ptr<AudioEngine> g_audio_engine;

/* Cached JVM and callback references for RTCP quality updates */
static JavaVM* g_jvm = nullptr;
static jobject g_quality_listener = nullptr;
static jmethodID g_on_quality_update = nullptr;

static void quality_callback(const RtcpQualityMetrics* metrics, void* user_data) {
    (void)user_data;
    if (!g_jvm || !g_quality_listener) return;

    JNIEnv* env;
    bool attached = false;
    if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        g_jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }

    if (env && g_on_quality_update) {
        env->CallVoidMethod(g_quality_listener, g_on_quality_update,
                            (jfloat)metrics->packet_loss_percent,
                            (jfloat)metrics->jitter_ms,
                            (jfloat)metrics->rtt_ms);
    }

    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

/*
 * Class: com.telcobright.sipphone.media.NativeMediaEngine
 */

JNIEXPORT jboolean JNICALL
Java_com_telcobright_sipphone_media_NativeMediaEngine_nativeStartMedia(
    JNIEnv* env, jobject thiz,
    jstring remoteHost, jint remoteRtpPort, jint remoteRtcpPort,
    jint localRtpPort, jint localRtcpPort,
    jint ssrc, jint payloadType,
    jint codecType, jint initialMode, jboolean dtx,
    jobject qualityListener) {

    (void)thiz;

    if (g_rtp_session) {
        LOGE("Media already started");
        return JNI_FALSE;
    }

    /* Store quality listener */
    if (qualityListener) {
        g_quality_listener = env->NewGlobalRef(qualityListener);
        jclass cls = env->GetObjectClass(qualityListener);
        g_on_quality_update = env->GetMethodID(cls, "onQualityUpdate", "(FFF)V");
    }

    const char* host = env->GetStringUTFChars(remoteHost, nullptr);

    RtpSessionConfig config = {};
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
    config.quality_user_data = nullptr;

    g_rtp_session = rtp_session_create(&config);
    env->ReleaseStringUTFChars(remoteHost, host);

    if (!g_rtp_session) {
        LOGE("Failed to create RTP session");
        return JNI_FALSE;
    }

    /* Start audio engine */
    g_audio_engine = std::make_unique<AudioEngine>();
    if (!g_audio_engine->start(g_rtp_session, (AmrCodecType)codecType)) {
        LOGE("Failed to start audio engine");
        rtp_session_destroy(g_rtp_session);
        g_rtp_session = nullptr;
        g_audio_engine.reset();
        return JNI_FALSE;
    }

    LOGD("Media started successfully");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_telcobright_sipphone_media_NativeMediaEngine_nativeStopMedia(
    JNIEnv* env, jobject thiz) {

    (void)thiz;

    if (g_audio_engine) {
        g_audio_engine->stop();
        g_audio_engine.reset();
    }

    if (g_rtp_session) {
        rtp_session_destroy(g_rtp_session);
        g_rtp_session = nullptr;
    }

    if (g_quality_listener) {
        env->DeleteGlobalRef(g_quality_listener);
        g_quality_listener = nullptr;
    }

    LOGD("Media stopped");
}

JNIEXPORT void JNICALL
Java_com_telcobright_sipphone_media_NativeMediaEngine_nativeSetMuted(
    JNIEnv* env, jobject thiz, jboolean muted) {

    (void)env; (void)thiz;
    if (g_audio_engine) {
        g_audio_engine->setMuted(muted);
    }
}

JNIEXPORT void JNICALL
Java_com_telcobright_sipphone_media_NativeMediaEngine_nativeSetMode(
    JNIEnv* env, jobject thiz, jint mode) {

    (void)env; (void)thiz;
    if (g_rtp_session) {
        rtp_session_set_mode(g_rtp_session, mode);
    }
}

JNIEXPORT void JNICALL
Java_com_telcobright_sipphone_media_NativeMediaEngine_nativeSetCmr(
    JNIEnv* env, jobject thiz, jint cmr) {

    (void)env; (void)thiz;
    if (g_audio_engine) {
        g_audio_engine->setCmr(cmr);
    }
}

JNIEXPORT jint JNICALL
Java_com_telcobright_sipphone_media_NativeMediaEngine_nativeGetMode(
    JNIEnv* env, jobject thiz) {

    (void)env; (void)thiz;
    return g_rtp_session ? rtp_session_get_mode(g_rtp_session) : -1;
}

JNIEXPORT void JNICALL
Java_com_telcobright_sipphone_media_NativeMediaEngine_nativeSendRtcp(
    JNIEnv* env, jobject thiz) {

    (void)env; (void)thiz;
    if (g_rtp_session) {
        rtp_session_send_rtcp(g_rtp_session);
    }
}

} /* extern "C" */
