#ifdef HAVE_PJMEDIA

#include "rtp_transport.h"
#include "rtp_packet.h"
#include <pjmedia/jbuf.h>
#include <pjmedia/rtp.h>
#include <pjmedia/rtcp.h>
#include <pj/os.h>
#include <pj/pool.h>
#include <pj/sock.h>
#include <pj/errno.h>
#include <pj/log.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <android/log.h>

#define LOG_TAG "RtpPjmedia"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Global pjlib init — called once */
static int g_pj_initialized = 0;
static pj_caching_pool g_cp;

static int ensure_pj_init(void) {
    if (g_pj_initialized) return 0;

    pj_status_t status = pj_init();
    if (status != PJ_SUCCESS) {
        LOGE("pj_init failed: %d", status);
        return -1;
    }
    pj_caching_pool_init(&g_cp, NULL, 1024 * 1024);
    g_pj_initialized = 1;
    LOGD("pjlib initialized");
    return 0;
}

struct RtpTransport {
    pj_pool_t* pool;
    pjmedia_jbuf* jbuf;
    pjmedia_rtp_session rtp_tx;
    pjmedia_rtp_session rtp_rx;
    pjmedia_rtcp_session rtcp;

    int rtp_socket;
    int rtcp_socket;
    struct sockaddr_in remote_rtp_addr;
    struct sockaddr_in remote_rtcp_addr;

    uint32_t ssrc;
    uint8_t payload_type;
    int sample_rate;
    int frame_samples;
    int frame_seq;

    rtp_quality_cb_t quality_cb;
    void* quality_user_data;
};

static int create_udp_socket(int port) {
    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock < 0) return -1;
    int flags = fcntl(sock, F_GETFL, 0);
    fcntl(sock, F_SETFL, flags | O_NONBLOCK);
    struct sockaddr_in addr = {0};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(port);
    if (bind(sock, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        close(sock);
        return -1;
    }
    return sock;
}

static RtpTransport* pjmedia_be_create(const RtpTransportConfig* config) {
    if (ensure_pj_init() != 0) return NULL;

    /* Register this thread with pjlib if not already */
    if (!pj_thread_is_registered()) {
        pj_thread_desc *desc = calloc(1, sizeof(pj_thread_desc));
        pj_thread_t* thread;
        pj_thread_register("pjmedia-create", *desc, &thread);
    }

    RtpTransport* t = (RtpTransport*)calloc(1, sizeof(RtpTransport));
    if (!t) return NULL;

    t->pool = pj_pool_create(&g_cp.factory, "rtp", 4096, 4096, NULL);
    if (!t->pool) {
        LOGE("Failed to create pj pool");
        free(t);
        return NULL;
    }

    t->ssrc = config->ssrc;
    t->payload_type = config->payload_type;
    t->sample_rate = config->sample_rate;
    t->frame_samples = config->sample_rate * config->frame_size_ms / 1000;
    t->quality_cb = config->quality_cb;
    t->quality_user_data = config->quality_user_data;

    /* RTP sessions */
    pjmedia_rtp_session_init(&t->rtp_tx, config->payload_type, config->ssrc);
    pjmedia_rtp_session_init(&t->rtp_rx, config->payload_type, 0);

    /* RTCP */
    pjmedia_rtcp_init(&t->rtcp, "rtcp", config->sample_rate / 1000,
                       t->frame_samples, config->ssrc);

    /* Adaptive jitter buffer */
    const pj_str_t jbuf_name = pj_str("jbuf");
    pj_status_t status = pjmedia_jbuf_create(t->pool, &jbuf_name,
                                              256,  /* max payload size (AMR or PCMU) */
                                              config->frame_size_ms,
                                              10, &t->jbuf);
    if (status != PJ_SUCCESS) {
        LOGE("jbuf_create failed: %d", status);
        pj_pool_release(t->pool);
        free(t);
        return NULL;
    }
    pjmedia_jbuf_set_adaptive(t->jbuf, 2, 4, 10);

    /* UDP sockets */
    t->rtp_socket = create_udp_socket(config->local_rtp_port);
    t->rtcp_socket = create_udp_socket(config->local_rtcp_port);
    if (t->rtp_socket < 0 || t->rtcp_socket < 0) {
        LOGE("Failed to create UDP sockets");
        if (t->rtp_socket >= 0) close(t->rtp_socket);
        if (t->rtcp_socket >= 0) close(t->rtcp_socket);
        pjmedia_jbuf_destroy(t->jbuf);
        pj_pool_release(t->pool);
        free(t);
        return NULL;
    }

    t->remote_rtp_addr.sin_family = AF_INET;
    t->remote_rtp_addr.sin_port = htons(config->remote_rtp_port);
    inet_pton(AF_INET, config->remote_host, &t->remote_rtp_addr.sin_addr);

    t->remote_rtcp_addr.sin_family = AF_INET;
    t->remote_rtcp_addr.sin_port = htons(config->remote_rtcp_port);
    inet_pton(AF_INET, config->remote_host, &t->remote_rtcp_addr.sin_addr);

    LOGD("pjmedia transport: local=%d -> %s:%d (jbuf adaptive)",
         config->local_rtp_port, config->remote_host, config->remote_rtp_port);

    return t;
}

static void pjmedia_be_destroy(RtpTransport* t) {
    if (!t) return;
    if (t->rtp_socket >= 0) close(t->rtp_socket);
    if (t->rtcp_socket >= 0) close(t->rtcp_socket);
    if (t->jbuf) pjmedia_jbuf_destroy(t->jbuf);
    if (t->pool) pj_pool_release(t->pool);
    /* Don't call pj_shutdown — global, reused across sessions */
    free(t);
}

static int pjmedia_be_send_payload(RtpTransport* t, const uint8_t* payload, int payload_len, int marker) {
    if (!t || !payload) return -1;

    /* Register thread if needed (send called from capture thread) */
    if (!pj_thread_is_registered()) {
        pj_thread_desc *sd = calloc(1, sizeof(pj_thread_desc));
        pj_thread_t* st;
        pj_thread_register("pjmedia-send", *sd, &st);
    }

    const void* rtp_hdr;
    int rtp_hdr_len;
    pjmedia_rtp_encode_rtp(&t->rtp_tx, t->payload_type, marker,
                            payload_len, t->frame_samples,
                            &rtp_hdr, &rtp_hdr_len);

    uint8_t packet[RTP_MAX_PACKET];
    memcpy(packet, rtp_hdr, rtp_hdr_len);
    memcpy(packet + rtp_hdr_len, payload, payload_len);

    pjmedia_rtcp_tx_rtp(&t->rtcp, payload_len);

    ssize_t sent = sendto(t->rtp_socket, packet, rtp_hdr_len + payload_len, 0,
                          (struct sockaddr*)&t->remote_rtp_addr,
                          sizeof(t->remote_rtp_addr));
    return (sent > 0) ? 0 : -1;
}

static int pjmedia_be_recv_payload(RtpTransport* t, uint8_t* payload, int max_len) {
    if (!t || !payload) return 0;

    /* Register thread if needed (recv called from playback thread) */
    if (!pj_thread_is_registered()) {
        pj_thread_desc *rd = calloc(1, sizeof(pj_thread_desc));
        pj_thread_t* rt;
        pj_thread_register("pjmedia-recv", *rd, &rt);
    }

    /* Read all available packets into jitter buffer */
    uint8_t packet[RTP_MAX_PACKET];
    while (1) {
        ssize_t received = recvfrom(t->rtp_socket, packet, sizeof(packet), 0, NULL, NULL);
        if (received <= 0) break;

        const pjmedia_rtp_hdr* rtp_hdr;
        const void* rtp_payload;
        unsigned rtp_payload_len;

        pj_status_t status = pjmedia_rtp_decode_rtp(&t->rtp_rx,
                                                      packet, (int)received,
                                                      &rtp_hdr, &rtp_payload, &rtp_payload_len);
        if (status != PJ_SUCCESS) continue;

        pjmedia_rtcp_rx_rtp(&t->rtcp, pj_ntohs(rtp_hdr->seq),
                             pj_ntohl(rtp_hdr->ts), rtp_payload_len);

        pjmedia_jbuf_put_frame(t->jbuf, rtp_payload, rtp_payload_len, t->frame_seq++);
    }

    /* Get from jitter buffer */
    char frame_type;
    pj_size_t frame_len = max_len;
    pj_uint32_t bit_info;
    pjmedia_jbuf_get_frame2(t->jbuf, payload, &frame_len, &frame_type, &bit_info);

    if (frame_type == PJMEDIA_JB_NORMAL_FRAME && frame_len > 0) {
        return (int)frame_len;
    }

    return 0;
}

static void pjmedia_be_send_rtcp(RtpTransport* t) {
    if (!t) return;

    void* rtcp_pkt;
    int rtcp_len;
    pjmedia_rtcp_build_rtcp(&t->rtcp, &rtcp_pkt, &rtcp_len);

    if (rtcp_len > 0) {
        sendto(t->rtcp_socket, rtcp_pkt, rtcp_len, 0,
               (struct sockaddr*)&t->remote_rtcp_addr,
               sizeof(t->remote_rtcp_addr));
    }

    if (t->quality_cb) {
        pjmedia_rtcp_stat *stat = &t->rtcp.stat;

        RtpQualityMetrics m = {0};
        unsigned total = stat->rx.pkt + stat->rx.loss;
        m.packet_loss_percent = total > 0 ? (float)stat->rx.loss * 100.0f / total : 0;
        /* pjmedia jitter.mean is in timestamp units × 1000 (pj_math_stat uses usec) */
        m.jitter_ms = (float)stat->rx.jitter.mean / 1000000.0f;
        m.rtt_ms = (float)stat->rtt.mean / 1000.0f;

        t->quality_cb(&m, t->quality_user_data);
    }
}

static RtpQualityMetrics pjmedia_be_get_quality(const RtpTransport* t) {
    RtpQualityMetrics m = {0};
    if (!t) return m;

    pjmedia_rtcp_stat *stat = &((RtpTransport*)t)->rtcp.stat;
    unsigned total = stat->rx.pkt + stat->rx.loss;
    m.packet_loss_percent = total > 0 ? (float)stat->rx.loss * 100.0f / total : 0;
    m.jitter_ms = (float)stat->rx.jitter.mean / 1000000.0f;
    m.rtt_ms = (float)stat->rtt.mean / 1000.0f;

    return m;
}

static const RtpTransportOps pjmedia_ops = {
    .name = "pjmedia",
    .type = RTP_TRANSPORT_PJMEDIA,
    .create = pjmedia_be_create,
    .destroy = pjmedia_be_destroy,
    .send_payload = pjmedia_be_send_payload,
    .recv_payload = pjmedia_be_recv_payload,
    .send_rtcp = pjmedia_be_send_rtcp,
    .get_quality = pjmedia_be_get_quality,
};

const RtpTransportOps* rtp_transport_pjmedia_get_ops(void) {
    return &pjmedia_ops;
}

#endif /* HAVE_PJMEDIA */
