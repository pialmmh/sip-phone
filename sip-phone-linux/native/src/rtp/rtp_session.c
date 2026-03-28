#include "rtp_session.h"
#include "rtp_transport.h"
#include "amr_rtp_payload.h"
#include "../codec/pcmu_codec.h"
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define LOG_TAG "RtpSession"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct RtpSession {
    /* Transport (pluggable: simple / pjmedia) */
    const RtpTransportOps* transport_ops;
    RtpTransport* transport;

    /* Codec type */
    int codec_type;    /* CODEC_TYPE_PCMU, CODEC_TYPE_AMR_NB, CODEC_TYPE_AMR_WB */

    /* AMR codec (NULL for PCMU) */
    AmrCodec* amr_codec;
    int current_mode;
};

static int get_sample_rate(int codec_type) {
    switch (codec_type) {
        case CODEC_TYPE_PCMU:   return 8000;
        case CODEC_TYPE_AMR_NB: return 8000;
        case CODEC_TYPE_AMR_WB: return 16000;
        default:                return 8000;
    }
}

static int get_frame_samples(int codec_type) {
    switch (codec_type) {
        case CODEC_TYPE_PCMU:   return 160;  /* 20ms at 8kHz */
        case CODEC_TYPE_AMR_NB: return 160;
        case CODEC_TYPE_AMR_WB: return 320;
        default:                return 160;
    }
}

RtpSession* rtp_session_create(const RtpSessionConfig* config) {
    if (!config) return NULL;

    RtpSession* s = (RtpSession*)calloc(1, sizeof(RtpSession));
    if (!s) return NULL;

    s->codec_type = config->codec_type;
    s->current_mode = config->initial_mode;

    /* Create AMR codec if needed (skip for PCMU) */
    if (config->codec_type != CODEC_TYPE_PCMU) {
        s->amr_codec = amr_codec_create((AmrCodecType)config->codec_type, config->dtx);
        if (!s->amr_codec) {
            LOGE("Failed to create AMR codec");
            free(s);
            return NULL;
        }
    }

    /* Select transport — use simple for now (pjmedia has init crash, TODO fix) */
    s->transport_ops = rtp_transport_get_ops(RTP_TRANSPORT_SIMPLE);
    if (!s->transport_ops) {
        LOGE("No RTP transport backend available");
        if (s->amr_codec) amr_codec_destroy(s->amr_codec);
        free(s);
        return NULL;
    }

    int sample_rate = get_sample_rate(config->codec_type);
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
        if (s->amr_codec) amr_codec_destroy(s->amr_codec);
        free(s);
        return NULL;
    }

    const char* codec_name = config->codec_type == CODEC_TYPE_PCMU ? "PCMU" :
                             config->codec_type == CODEC_TYPE_AMR_WB ? "AMR-WB" : "AMR-NB";
    LOGD("RTP session: transport=%s codec=%s local=%d remote=%s:%d",
         s->transport_ops->name, codec_name,
         config->local_rtp_port, config->remote_host, config->remote_rtp_port);

    return s;
}

void rtp_session_destroy(RtpSession* session) {
    if (!session) return;
    if (session->transport) session->transport_ops->destroy(session->transport);
    if (session->amr_codec) amr_codec_destroy(session->amr_codec);
    free(session);
}

/* === SEND === */

static int send_pcmu(RtpSession* s, const int16_t* pcm) {
    uint8_t payload[PCMU_FRAME_BYTES];
    pcmu_encode(pcm, payload, PCMU_FRAME_SAMPLES);
    return s->transport_ops->send_payload(s->transport, payload, PCMU_FRAME_BYTES, 0);
}

static int send_amr(RtpSession* s, const int16_t* pcm, int cmr) {
    uint8_t amr_frame[64];
    int amr_len = amr_codec_encode(s->amr_codec, s->current_mode, pcm, amr_frame);
    if (amr_len <= 0) return -1;

    /* Skip TOC byte from encoder output */
    uint8_t* speech = amr_frame + 1;
    int speech_len = amr_len - 1;

    uint8_t payload[128];
    int cmr_val = (cmr >= 0) ? cmr : AMR_RTP_CMR_NO_REQUEST;
    int payload_len = amr_rtp_payload_build_be(cmr_val, s->current_mode, 1,
                                                speech, speech_len, payload);
    if (payload_len <= 0) return -1;

    return s->transport_ops->send_payload(s->transport, payload, payload_len, 0);
}

int rtp_session_send_frame(RtpSession* session, const int16_t* pcm, int cmr) {
    if (!session || !pcm) return -1;

    if (session->codec_type == CODEC_TYPE_PCMU) {
        return send_pcmu(session, pcm);
    } else {
        return send_amr(session, pcm, cmr);
    }
}

/* === RECEIVE === */

static int recv_pcmu(RtpSession* s, int16_t* pcm_out) {
    uint8_t payload[256];
    int len = s->transport_ops->recv_payload(s->transport, payload, sizeof(payload));
    if (len <= 0) return 0;

    return pcmu_decode(payload, pcm_out, len);
}

static int recv_amr(RtpSession* s, int16_t* pcm_out, int* cmr_received) {
    uint8_t payload[256];
    int payload_len = s->transport_ops->recv_payload(s->transport, payload, sizeof(payload));
    if (payload_len <= 0) return 0;

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

    /* TOC byte + frame data for decoder */
    uint8_t decoder_input[256];
    decoder_input[0] = (uint8_t)((frame_type << 3) | (quality << 2));
    memcpy(&decoder_input[1], amr_frame_buf, amr_frame_len);

    int samples = amr_codec_decode(s->amr_codec, decoder_input, amr_frame_len + 1, pcm_out);
    return samples > 0 ? samples : 0;
}

int rtp_session_receive_frame(RtpSession* session, int16_t* pcm_out,
                              int* cmr_received) {
    if (!session || !pcm_out) return 0;
    if (cmr_received) *cmr_received = -1;

    if (session->codec_type == CODEC_TYPE_PCMU) {
        return recv_pcmu(session, pcm_out);
    } else {
        return recv_amr(session, pcm_out, cmr_received);
    }
}

/* === RTCP / Mode === */

void rtp_session_process_rtcp(RtpSession* session) {
    (void)session;
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
    return empty;
}
