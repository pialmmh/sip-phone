#include "rtcp_handler.h"
#include "rtp_packet.h"
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <arpa/inet.h>
#include <android/log.h>

#define LOG_TAG "RtcpHandler"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

struct RtcpSession {
    uint32_t local_ssrc;
    int      sample_rate;

    /* Sender stats */
    uint32_t packets_sent;
    uint32_t octets_sent;
    uint32_t last_rtp_timestamp_sent;

    /* Receiver stats */
    uint32_t packets_received;
    uint32_t packets_expected;
    uint16_t max_seq_received;
    uint16_t base_seq;
    int      seq_initialized;
    uint32_t last_rtp_timestamp_received;
    uint64_t last_rtp_arrival_time_us;
    uint32_t jitter_q4;             /* Jitter in Q4 fixed-point (timestamp units * 16) */
    uint32_t remote_ssrc;

    /* For RTT computation */
    uint32_t last_sr_ntp_msw;       /* NTP timestamp from last received SR */
    uint32_t last_sr_ntp_lsw;
    uint64_t last_sr_receive_time_us;

    /* Quality metrics */
    RtcpQualityMetrics quality;
    rtcp_quality_callback_t callback;
    void* user_data;
};

static uint64_t now_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000ULL + (uint64_t)ts.tv_nsec / 1000ULL;
}

static uint64_t now_ms(void) {
    return now_us() / 1000ULL;
}

RtcpSession* rtcp_session_create(uint32_t local_ssrc, int sample_rate,
                                  rtcp_quality_callback_t callback,
                                  void* user_data) {
    RtcpSession* s = (RtcpSession*)calloc(1, sizeof(RtcpSession));
    if (!s) return NULL;

    s->local_ssrc = local_ssrc;
    s->sample_rate = sample_rate;
    s->callback = callback;
    s->user_data = user_data;

    return s;
}

void rtcp_session_destroy(RtcpSession* session) {
    free(session);
}

void rtcp_on_rtp_sent(RtcpSession* session, uint16_t seq, uint32_t timestamp,
                      int payload_bytes) {
    if (!session) return;
    (void)seq;
    session->packets_sent++;
    session->octets_sent += payload_bytes;
    session->last_rtp_timestamp_sent = timestamp;
}

void rtcp_on_rtp_received(RtcpSession* session, uint16_t seq, uint32_t timestamp) {
    if (!session) return;

    uint64_t arrival = now_us();

    if (!session->seq_initialized) {
        session->base_seq = seq;
        session->max_seq_received = seq;
        session->seq_initialized = 1;
        session->last_rtp_timestamp_received = timestamp;
        session->last_rtp_arrival_time_us = arrival;
        session->packets_received = 1;
        return;
    }

    session->packets_received++;

    /* Update max sequence (simple, no wrap handling for brevity) */
    if ((int16_t)(seq - session->max_seq_received) > 0) {
        session->max_seq_received = seq;
    }

    /* Jitter calculation per RFC 3550 A.8 */
    int64_t transit_diff;
    uint32_t arrival_ts = (uint32_t)((arrival - session->last_rtp_arrival_time_us)
                          * session->sample_rate / 1000000);
    uint32_t rtp_diff = timestamp - session->last_rtp_timestamp_received;

    transit_diff = (int64_t)arrival_ts - (int64_t)rtp_diff;
    if (transit_diff < 0) transit_diff = -transit_diff;

    /* Jitter estimation: J(i) = J(i-1) + (|D(i)| - J(i-1)) / 16 */
    session->jitter_q4 += ((uint32_t)transit_diff - (session->jitter_q4 >> 4));

    session->last_rtp_timestamp_received = timestamp;
    session->last_rtp_arrival_time_us = arrival;
}

int rtcp_process_incoming(RtcpSession* session, const uint8_t* data, int len) {
    if (!session || !data || len < 8) return -1;

    uint8_t pt = data[1];

    if (pt == RTCP_PT_SR && len >= 28) {
        /* Parse Sender Report — extract NTP timestamp for RTT */
        uint32_t ntp_msw, ntp_lsw;
        memcpy(&ntp_msw, &data[8], 4);
        memcpy(&ntp_lsw, &data[12], 4);
        session->last_sr_ntp_msw = ntohl(ntp_msw);
        session->last_sr_ntp_lsw = ntohl(ntp_lsw);
        session->last_sr_receive_time_us = now_us();

        uint32_t sender_ssrc;
        memcpy(&sender_ssrc, &data[4], 4);
        session->remote_ssrc = ntohl(sender_ssrc);

        /* If SR contains report blocks (RC > 0), parse them */
        uint8_t rc = data[0] & 0x1F;
        if (rc > 0 && len >= 52) {
            /* First report block starts at offset 28 */
            const uint8_t* rb = &data[28];

            uint8_t fraction_lost = rb[4];
            uint32_t jitter_net;
            memcpy(&jitter_net, &rb[12], 4);
            uint32_t jitter = ntohl(jitter_net);

            session->quality.packet_loss_percent = (fraction_lost / 256.0f) * 100.0f;
            session->quality.jitter_ms = (float)jitter * 1000.0f / session->sample_rate;
            session->quality.timestamp_ms = now_ms();

            LOGD("RTCP SR: loss=%.1f%% jitter=%.1fms",
                 session->quality.packet_loss_percent,
                 session->quality.jitter_ms);

            if (session->callback) {
                session->callback(&session->quality, session->user_data);
            }
        }
    } else if (pt == RTCP_PT_RR && len >= 32) {
        /* Parse Receiver Report */
        const uint8_t* rb = &data[8];

        uint8_t fraction_lost = rb[4];
        uint32_t jitter_net;
        memcpy(&jitter_net, &rb[12], 4);
        uint32_t jitter = ntohl(jitter_net);

        /* RTT from DLSR and LSR */
        uint32_t lsr_net, dlsr_net;
        memcpy(&lsr_net, &rb[16], 4);
        memcpy(&dlsr_net, &rb[20], 4);
        uint32_t lsr = ntohl(lsr_net);
        uint32_t dlsr = ntohl(dlsr_net);

        if (lsr != 0) {
            /* Compute RTT: A = current NTP compact, RTT = A - LSR - DLSR */
            uint64_t now_ntp_us = now_us();
            /* Rough NTP compact timestamp from monotonic clock */
            uint32_t a = (uint32_t)((now_ntp_us & 0xFFFF0000ULL) >> 16);
            int32_t rtt_units = (int32_t)(a - lsr - dlsr);
            if (rtt_units > 0) {
                session->quality.rtt_ms = (float)rtt_units / 65.536f;
            }
        }

        session->quality.packet_loss_percent = (fraction_lost / 256.0f) * 100.0f;
        session->quality.jitter_ms = (float)jitter * 1000.0f / session->sample_rate;
        session->quality.timestamp_ms = now_ms();

        LOGD("RTCP RR: loss=%.1f%% jitter=%.1fms rtt=%.1fms",
             session->quality.packet_loss_percent,
             session->quality.jitter_ms,
             session->quality.rtt_ms);

        if (session->callback) {
            session->callback(&session->quality, session->user_data);
        }
    }

    return 0;
}

int rtcp_build_receiver_report(RtcpSession* session, uint8_t* buf, int buf_len) {
    if (!session || !buf || buf_len < 32) return -1;

    memset(buf, 0, 32);

    /* RTCP header: V=2, P=0, RC=1, PT=201(RR), length=7 (32 bytes) */
    buf[0] = (RTP_VERSION << 6) | 1;   /* RC=1 */
    buf[1] = RTCP_PT_RR;
    buf[2] = 0; buf[3] = 7;            /* length in 32-bit words minus 1 */

    /* Our SSRC */
    uint32_t ssrc_net = htonl(session->local_ssrc);
    memcpy(&buf[4], &ssrc_net, 4);

    /* Report block */
    uint32_t remote_ssrc_net = htonl(session->remote_ssrc);
    memcpy(&buf[8], &remote_ssrc_net, 4);

    /* Fraction lost and cumulative lost */
    uint32_t expected = (uint32_t)(session->max_seq_received - session->base_seq + 1);
    uint32_t lost = (expected > session->packets_received) ?
                    (expected - session->packets_received) : 0;
    uint8_t fraction = 0;
    if (expected > 0) {
        fraction = (uint8_t)((lost * 256) / expected);
    }

    buf[12] = fraction;
    /* Cumulative lost (24 bits) */
    buf[13] = (uint8_t)((lost >> 16) & 0xFF);
    buf[14] = (uint8_t)((lost >> 8) & 0xFF);
    buf[15] = (uint8_t)(lost & 0xFF);

    /* Highest seq received */
    uint32_t ext_seq_net = htonl((uint32_t)session->max_seq_received);
    memcpy(&buf[16], &ext_seq_net, 4);

    /* Jitter */
    uint32_t jitter_net = htonl(session->jitter_q4 >> 4);
    memcpy(&buf[20], &jitter_net, 4);

    /* LSR — middle 32 bits of last SR NTP timestamp */
    uint32_t lsr = (session->last_sr_ntp_msw << 16) |
                   (session->last_sr_ntp_lsw >> 16);
    uint32_t lsr_net = htonl(lsr);
    memcpy(&buf[24], &lsr_net, 4);

    /* DLSR — delay since last SR in 1/65536 sec */
    uint32_t dlsr = 0;
    if (session->last_sr_receive_time_us > 0) {
        uint64_t delay_us = now_us() - session->last_sr_receive_time_us;
        dlsr = (uint32_t)(delay_us * 65536 / 1000000);
    }
    uint32_t dlsr_net = htonl(dlsr);
    memcpy(&buf[28], &dlsr_net, 4);

    return 32;
}

int rtcp_build_sender_report(RtcpSession* session, uint8_t* buf, int buf_len) {
    if (!session || !buf || buf_len < 28) return -1;

    memset(buf, 0, 28);

    /* RTCP header: V=2, P=0, RC=0, PT=200(SR), length=6 (28 bytes) */
    buf[0] = (RTP_VERSION << 6);
    buf[1] = RTCP_PT_SR;
    buf[2] = 0; buf[3] = 6;

    uint32_t ssrc_net = htonl(session->local_ssrc);
    memcpy(&buf[4], &ssrc_net, 4);

    /* NTP timestamp (approximate from monotonic clock) */
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    uint32_t ntp_msw = htonl((uint32_t)(ts.tv_sec + 2208988800UL)); /* NTP epoch */
    uint32_t ntp_lsw = htonl((uint32_t)((uint64_t)ts.tv_nsec * 4294967296ULL / 1000000000ULL));
    memcpy(&buf[8], &ntp_msw, 4);
    memcpy(&buf[12], &ntp_lsw, 4);

    /* RTP timestamp */
    uint32_t rtp_ts_net = htonl(session->last_rtp_timestamp_sent);
    memcpy(&buf[16], &rtp_ts_net, 4);

    /* Packet count */
    uint32_t pc_net = htonl(session->packets_sent);
    memcpy(&buf[20], &pc_net, 4);

    /* Octet count */
    uint32_t oc_net = htonl(session->octets_sent);
    memcpy(&buf[24], &oc_net, 4);

    return 28;
}

RtcpQualityMetrics rtcp_get_quality(const RtcpSession* session) {
    if (!session) {
        RtcpQualityMetrics empty = {0};
        return empty;
    }
    return session->quality;
}
