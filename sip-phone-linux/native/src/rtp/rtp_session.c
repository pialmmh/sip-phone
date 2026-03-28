#include "rtp_session.h"
#include "rtp_transport.h"
#include "amr_rtp_payload.h"
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define LOG_TAG "RtpSession"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct RtpSession {
    /* Transport (pluggable: simple / oRTP / pjmedia) */
    const RtpTransportOps* transport_ops;
    RtpTransport* transport;

    /* Codec */
    AmrCodec* codec;
    AmrCodecType codec_type;
    int current_mode;
};

RtpSession* rtp_session_create(const RtpSessionConfig* config) {
    if (!config) return NULL;

    RtpSession* s = (RtpSession*)calloc(1, sizeof(RtpSession));
    if (!s) return NULL;

    s->codec_type = config->codec_type;
    s->current_mode = config->initial_mode;

    /* Create codec */
    s->codec = amr_codec_create(config->codec_type, config->dtx);
    if (!s->codec) {
        LOGE("Failed to create AMR codec");
        free(s);
        return NULL;
    }

    /* Select transport backend — try oRTP first, fall back to simple */
    s->transport_ops = rtp_transport_get_ops(RTP_TRANSPORT_ORTP);
    if (!s->transport_ops) {
        s->transport_ops = rtp_transport_get_ops(RTP_TRANSPORT_SIMPLE);
    }
    if (!s->transport_ops) {
        LOGE("No RTP transport backend available");
        amr_codec_destroy(s->codec);
        free(s);
        return NULL;
    }

    /* Create transport */
    int sample_rate = amr_codec_sample_rate(s->codec);
    RtpTransportConfig tc = {
        .remote_host = config->remote_host,
        .remote_rtp_port = config->remote_rtp_port,
        .remote_rtcp_port = config->remote_rtcp_port,
        .local_rtp_port = config->local_rtp_port,
        .local_rtcp_port = config->local_rtcp_port,
        .ssrc = config->ssrc,
        .payload_type = config->payload_type,
        .sample_rate = sample_rate,
        .frame_size_ms = 20,
        .quality_cb = config->quality_callback,
        .quality_user_data = config->quality_user_data,
    };

    s->transport = s->transport_ops->create(&tc);
    if (!s->transport) {
        LOGE("Failed to create %s transport", s->transport_ops->name);
        amr_codec_destroy(s->codec);
        free(s);
        return NULL;
    }

    LOGD("RTP session created: transport=%s, local=%d/%d remote=%s:%d/%d",
         s->transport_ops->name,
         config->local_rtp_port, config->local_rtcp_port,
         config->remote_host, config->remote_rtp_port, config->remote_rtcp_port);

    return s;
}

void rtp_session_destroy(RtpSession* session) {
    if (!session) return;
    if (session->transport) session->transport_ops->destroy(session->transport);
    if (session->codec) amr_codec_destroy(session->codec);
    free(session);
}

int rtp_session_send_frame(RtpSession* session, const int16_t* pcm, int cmr) {
    if (!session || !pcm) return -1;

    /* Encode PCM to AMR */
    uint8_t amr_frame[64];
    int amr_len = amr_codec_encode(session->codec, session->current_mode,
                                    pcm, amr_frame);
    if (amr_len <= 0) return -1;

    /* Skip TOC byte from encoder output */
    uint8_t* speech_data = amr_frame + 1;
    int speech_len = amr_len - 1;

    /* Build bandwidth-efficient AMR RTP payload */
    uint8_t payload[128];
    int cmr_value = (cmr >= 0) ? cmr : AMR_RTP_CMR_NO_REQUEST;
    int payload_len = amr_rtp_payload_build_be(cmr_value, session->current_mode, 1,
                                                speech_data, speech_len, payload);
    if (payload_len <= 0) return -1;

    /* Send via transport */
    return session->transport_ops->send_payload(session->transport, payload, payload_len, 0);
}

int rtp_session_receive_frame(RtpSession* session, int16_t* pcm_out,
                              int* cmr_received) {
    if (!session || !pcm_out) return 0;
    if (cmr_received) *cmr_received = -1;

    /* Receive one RTP payload via transport */
    uint8_t payload[256];
    int payload_len = session->transport_ops->recv_payload(session->transport, payload, sizeof(payload));
    if (payload_len <= 0) return 0;

    /* Parse bandwidth-efficient AMR payload */
    int cmr, frame_type, quality;
    uint8_t amr_frame_buf[64];
    int amr_frame_len;

    if (amr_rtp_payload_parse_be(payload, payload_len,
                                  &cmr, &frame_type, &quality,
                                  amr_frame_buf, &amr_frame_len) != 0) {
        return 0;
    }

    if (cmr_received && cmr != AMR_RTP_CMR_NO_REQUEST) {
        *cmr_received = cmr;
    }

    /* Build decoder input: TOC byte + frame data */
    uint8_t decoder_input[256];
    decoder_input[0] = (uint8_t)((frame_type << 3) | (quality << 2));
    memcpy(&decoder_input[1], amr_frame_buf, amr_frame_len);

    int samples = amr_codec_decode(session->codec, decoder_input, amr_frame_len + 1, pcm_out);
    return samples > 0 ? samples : 0;
}

void rtp_session_process_rtcp(RtpSession* session) {
    (void)session; /* Handled by transport internally */
}

void rtp_session_send_rtcp(RtpSession* session) {
    if (session && session->transport) {
        session->transport_ops->send_rtcp(session->transport);
    }
}

void rtp_session_set_mode(RtpSession* session, int mode) {
    if (session) session->current_mode = mode;
}

int rtp_session_get_mode(const RtpSession* session) {
    return session ? session->current_mode : -1;
}

RtcpQualityMetrics rtp_session_get_quality(const RtpSession* session) {
    RtcpQualityMetrics empty = {0};
    return empty; /* TODO: map from transport quality metrics */
}
