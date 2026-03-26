#include "rtp_session.h"
#include "amr_rtp_payload.h"
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <android/log.h>

#define LOG_TAG "RtpSession"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct RtpSession {
    /* Sockets */
    int rtp_socket;
    int rtcp_socket;
    struct sockaddr_in remote_rtp_addr;
    struct sockaddr_in remote_rtcp_addr;

    /* RTP state */
    uint16_t seq;
    uint32_t timestamp;
    uint32_t ssrc;
    uint8_t  payload_type;

    /* Codec */
    AmrCodec* codec;
    AmrCodecType codec_type;
    int current_mode;

    /* RTCP */
    RtcpSession* rtcp;

    /* Jitter buffer */
    JitterBuffer jitter_buffer;
};

static int create_udp_socket(int port) {
    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock < 0) return -1;

    /* Set non-blocking */
    int flags = fcntl(sock, F_GETFL, 0);
    fcntl(sock, F_SETFL, flags | O_NONBLOCK);

    /* Bind */
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(port);

    if (bind(sock, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        LOGE("Failed to bind UDP port %d: %s", port, strerror(errno));
        close(sock);
        return -1;
    }

    return sock;
}

RtpSession* rtp_session_create(const RtpSessionConfig* config) {
    if (!config) return NULL;

    RtpSession* s = (RtpSession*)calloc(1, sizeof(RtpSession));
    if (!s) return NULL;

    s->ssrc = config->ssrc;
    s->payload_type = config->payload_type;
    s->codec_type = config->codec_type;
    s->current_mode = config->initial_mode;

    /* Create codec */
    s->codec = amr_codec_create(config->codec_type, config->dtx);
    if (!s->codec) {
        LOGE("Failed to create AMR codec");
        free(s);
        return NULL;
    }

    /* Create sockets */
    s->rtp_socket = create_udp_socket(config->local_rtp_port);
    s->rtcp_socket = create_udp_socket(config->local_rtcp_port);
    if (s->rtp_socket < 0 || s->rtcp_socket < 0) {
        LOGE("Failed to create UDP sockets");
        rtp_session_destroy(s);
        return NULL;
    }

    /* Remote addresses */
    memset(&s->remote_rtp_addr, 0, sizeof(s->remote_rtp_addr));
    s->remote_rtp_addr.sin_family = AF_INET;
    s->remote_rtp_addr.sin_port = htons(config->remote_rtp_port);
    inet_pton(AF_INET, config->remote_host, &s->remote_rtp_addr.sin_addr);

    memset(&s->remote_rtcp_addr, 0, sizeof(s->remote_rtcp_addr));
    s->remote_rtcp_addr.sin_family = AF_INET;
    s->remote_rtcp_addr.sin_port = htons(config->remote_rtcp_port);
    inet_pton(AF_INET, config->remote_host, &s->remote_rtcp_addr.sin_addr);

    /* RTCP session */
    int sample_rate = amr_codec_sample_rate(s->codec);
    s->rtcp = rtcp_session_create(s->ssrc, sample_rate,
                                   config->quality_callback,
                                   config->quality_user_data);

    /* Jitter buffer: 40-200ms */
    jitter_buffer_init(&s->jitter_buffer, 40, 200, sample_rate);

    /* Random initial sequence number */
    s->seq = (uint16_t)(rand() & 0xFFFF);
    s->timestamp = (uint32_t)(rand() & 0xFFFFFFFF);

    LOGD("RTP session created: local=%d/%d remote=%s:%d/%d",
         config->local_rtp_port, config->local_rtcp_port,
         config->remote_host, config->remote_rtp_port, config->remote_rtcp_port);

    return s;
}

void rtp_session_destroy(RtpSession* session) {
    if (!session) return;

    if (session->rtp_socket >= 0) close(session->rtp_socket);
    if (session->rtcp_socket >= 0) close(session->rtcp_socket);
    if (session->codec) amr_codec_destroy(session->codec);
    if (session->rtcp) rtcp_session_destroy(session->rtcp);

    free(session);
}

int rtp_session_send_frame(RtpSession* session, const int16_t* pcm, int cmr) {
    if (!session || !pcm) return -1;

    /* Encode PCM to AMR */
    uint8_t amr_frame[64];
    int amr_len = amr_codec_encode(session->codec, session->current_mode,
                                    pcm, amr_frame);
    if (amr_len <= 0) return -1;

    /* Build AMR RTP payload (RFC 4867 bandwidth-efficient) */
    uint8_t payload[128];
    int cmr_value = (cmr >= 0) ? cmr : AMR_RTP_CMR_NO_REQUEST;
    int payload_len = amr_rtp_payload_build_be(cmr_value, session->current_mode, 1,
                                                amr_frame, amr_len, payload);
    if (payload_len <= 0) return -1;

    /* Build RTP packet */
    uint8_t packet[RTP_MAX_PACKET];
    RtpHeader hdr = {
        .version = RTP_VERSION,
        .padding = 0,
        .extension = 0,
        .csrc_count = 0,
        .marker = 0,
        .payload_type = session->payload_type,
        .sequence = session->seq,
        .timestamp = session->timestamp,
        .ssrc = session->ssrc
    };

    int hdr_len = rtp_header_serialize(&hdr, packet, sizeof(packet));
    memcpy(&packet[hdr_len], payload, payload_len);
    int total_len = hdr_len + payload_len;

    /* Send */
    ssize_t sent = sendto(session->rtp_socket, packet, total_len, 0,
                          (struct sockaddr*)&session->remote_rtp_addr,
                          sizeof(session->remote_rtp_addr));

    if (sent < 0) {
        LOGE("RTP send failed: %s", strerror(errno));
        return -1;
    }

    /* Update RTCP stats */
    rtcp_on_rtp_sent(session->rtcp, session->seq, session->timestamp, payload_len);

    /* Advance sequence and timestamp */
    session->seq++;
    session->timestamp += amr_codec_frame_samples(session->codec);

    return 0;
}

int rtp_session_receive_frame(RtpSession* session, int16_t* pcm_out,
                              int* cmr_received) {
    if (!session || !pcm_out) return 0;
    if (cmr_received) *cmr_received = -1;

    /* Read all available RTP packets into jitter buffer */
    uint8_t packet[RTP_MAX_PACKET];
    while (1) {
        ssize_t received = recvfrom(session->rtp_socket, packet, sizeof(packet),
                                     0, NULL, NULL);
        if (received <= 0) break;

        RtpHeader hdr;
        int hdr_len = rtp_header_parse(packet, (int)received, &hdr);
        if (hdr_len < 0) continue;

        /* Parse AMR RTP payload */
        const uint8_t* payload = &packet[hdr_len];
        int payload_len = (int)received - hdr_len;

        int cmr, frame_type, quality;
        uint8_t amr_frame_buf[64];
        int amr_frame_len;

        if (amr_rtp_payload_parse_be(payload, payload_len,
                                      &cmr, &frame_type, &quality,
                                      amr_frame_buf, &amr_frame_len) == 0) {

            /* Store in jitter buffer (raw AMR frame with TOC prepended for decoder) */
            uint8_t decoder_input[256];
            /* TOC byte for decoder: [FT(4)][Q(1)][padding(3)] */
            decoder_input[0] = (uint8_t)((frame_type << 3) | (quality << 2));
            memcpy(&decoder_input[1], amr_frame_buf, amr_frame_len);

            jitter_buffer_put(&session->jitter_buffer, hdr.sequence, hdr.timestamp,
                             decoder_input, amr_frame_len + 1);

            rtcp_on_rtp_received(session->rtcp, hdr.sequence, hdr.timestamp);

            /* Report CMR from the latest packet */
            if (cmr_received && cmr != AMR_RTP_CMR_NO_REQUEST) {
                *cmr_received = cmr;
            }
        }
    }

    /* Get next frame from jitter buffer */
    uint8_t amr_data[256];
    int amr_data_len;
    if (jitter_buffer_get(&session->jitter_buffer, amr_data, &amr_data_len)) {
        /* Decode AMR to PCM */
        int samples = amr_codec_decode(session->codec, amr_data, amr_data_len, pcm_out);
        return samples > 0 ? samples : 0;
    }

    /* No frame available — fill with silence (PLC could be added here) */
    int frame_samples = amr_codec_frame_samples(session->codec);
    memset(pcm_out, 0, frame_samples * sizeof(int16_t));
    return frame_samples;
}

void rtp_session_process_rtcp(RtpSession* session) {
    if (!session) return;

    uint8_t buf[512];
    while (1) {
        ssize_t received = recvfrom(session->rtcp_socket, buf, sizeof(buf),
                                     0, NULL, NULL);
        if (received <= 0) break;
        rtcp_process_incoming(session->rtcp, buf, (int)received);
    }
}

void rtp_session_send_rtcp(RtpSession* session) {
    if (!session) return;

    uint8_t buf[128];

    /* Send SR */
    int sr_len = rtcp_build_sender_report(session->rtcp, buf, sizeof(buf));
    if (sr_len > 0) {
        sendto(session->rtcp_socket, buf, sr_len, 0,
               (struct sockaddr*)&session->remote_rtcp_addr,
               sizeof(session->remote_rtcp_addr));
    }

    /* Send RR */
    int rr_len = rtcp_build_receiver_report(session->rtcp, buf, sizeof(buf));
    if (rr_len > 0) {
        sendto(session->rtcp_socket, buf, rr_len, 0,
               (struct sockaddr*)&session->remote_rtcp_addr,
               sizeof(session->remote_rtcp_addr));
    }
}

void rtp_session_set_mode(RtpSession* session, int mode) {
    if (session) session->current_mode = mode;
}

int rtp_session_get_mode(const RtpSession* session) {
    return session ? session->current_mode : -1;
}

RtcpQualityMetrics rtp_session_get_quality(const RtpSession* session) {
    if (session && session->rtcp) {
        return rtcp_get_quality(session->rtcp);
    }
    RtcpQualityMetrics empty = {0};
    return empty;
}
