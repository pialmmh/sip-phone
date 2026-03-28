#include "rtp_transport.h"
#include "rtp_packet.h"
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <android/log.h>

#define LOG_TAG "RtpSimple"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * Simple RTP transport — direct UDP send/recv, no jitter buffer.
 * Works reliably over VPN. No reordering or loss concealment.
 */

struct RtpTransport {
    int rtp_socket;
    int rtcp_socket;
    struct sockaddr_in remote_rtp_addr;
    struct sockaddr_in remote_rtcp_addr;

    uint16_t seq;
    uint32_t timestamp;
    uint32_t ssrc;
    uint8_t  payload_type;
    int      sample_rate;
    int      frame_samples;

    /* Basic RTCP stats */
    uint32_t packets_sent;
    uint32_t packets_received;
    uint32_t octets_sent;

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
        LOGE("Bind failed on port %d: %s", port, strerror(errno));
        close(sock);
        return -1;
    }
    return sock;
}

static RtpTransport* simple_create(const RtpTransportConfig* config) {
    RtpTransport* t = (RtpTransport*)calloc(1, sizeof(RtpTransport));
    if (!t) return NULL;

    t->ssrc = config->ssrc;
    t->payload_type = config->payload_type;
    t->sample_rate = config->sample_rate;
    t->frame_samples = config->sample_rate * config->frame_size_ms / 1000;
    t->quality_cb = config->quality_cb;
    t->quality_user_data = config->quality_user_data;

    t->rtp_socket = create_udp_socket(config->local_rtp_port);
    t->rtcp_socket = create_udp_socket(config->local_rtcp_port);
    if (t->rtp_socket < 0 || t->rtcp_socket < 0) {
        if (t->rtp_socket >= 0) close(t->rtp_socket);
        if (t->rtcp_socket >= 0) close(t->rtcp_socket);
        free(t);
        return NULL;
    }

    t->remote_rtp_addr.sin_family = AF_INET;
    t->remote_rtp_addr.sin_port = htons(config->remote_rtp_port);
    inet_pton(AF_INET, config->remote_host, &t->remote_rtp_addr.sin_addr);

    t->remote_rtcp_addr.sin_family = AF_INET;
    t->remote_rtcp_addr.sin_port = htons(config->remote_rtcp_port);
    inet_pton(AF_INET, config->remote_host, &t->remote_rtcp_addr.sin_addr);

    t->seq = (uint16_t)(rand() & 0xFFFF);
    t->timestamp = (uint32_t)(rand() & 0xFFFFFFFF);

    LOGD("Simple transport: local=%d -> %s:%d",
         config->local_rtp_port, config->remote_host, config->remote_rtp_port);
    return t;
}

static void simple_destroy(RtpTransport* t) {
    if (!t) return;
    if (t->rtp_socket >= 0) close(t->rtp_socket);
    if (t->rtcp_socket >= 0) close(t->rtcp_socket);
    free(t);
}

static int simple_send_payload(RtpTransport* t, const uint8_t* payload, int payload_len, int marker) {
    if (!t || !payload) return -1;

    uint8_t packet[RTP_MAX_PACKET];
    RtpHeader hdr = {
        .version = RTP_VERSION,
        .marker = marker ? 1 : 0,
        .payload_type = t->payload_type,
        .sequence = t->seq,
        .timestamp = t->timestamp,
        .ssrc = t->ssrc
    };

    int hdr_len = rtp_header_serialize(&hdr, packet, sizeof(packet));
    memcpy(&packet[hdr_len], payload, payload_len);

    ssize_t sent = sendto(t->rtp_socket, packet, hdr_len + payload_len, 0,
                          (struct sockaddr*)&t->remote_rtp_addr,
                          sizeof(t->remote_rtp_addr));

    t->seq++;
    t->timestamp += t->frame_samples;
    t->packets_sent++;
    t->octets_sent += payload_len;

    return (sent > 0) ? 0 : -1;
}

static int simple_recv_payload(RtpTransport* t, uint8_t* payload, int max_len) {
    if (!t || !payload) return 0;

    uint8_t packet[RTP_MAX_PACKET];
    ssize_t received = recvfrom(t->rtp_socket, packet, sizeof(packet), 0, NULL, NULL);
    if (received <= 0) return 0;

    RtpHeader hdr;
    int hdr_len = rtp_header_parse(packet, (int)received, &hdr);
    if (hdr_len < 0) return 0;

    int payload_len = (int)received - hdr_len;
    if (payload_len > max_len) payload_len = max_len;

    memcpy(payload, &packet[hdr_len], payload_len);
    t->packets_received++;

    return payload_len;
}

static void simple_send_rtcp(RtpTransport* t) {
    (void)t; /* Basic impl — no RTCP for now */
}

static RtpQualityMetrics simple_get_quality(const RtpTransport* t) {
    RtpQualityMetrics m = {0};
    (void)t;
    return m;
}

/* Export vtable */
static const RtpTransportOps simple_ops = {
    .name = "simple",
    .type = RTP_TRANSPORT_SIMPLE,
    .create = simple_create,
    .destroy = simple_destroy,
    .send_payload = simple_send_payload,
    .recv_payload = simple_recv_payload,
    .send_rtcp = simple_send_rtcp,
    .get_quality = simple_get_quality,
};

const RtpTransportOps* rtp_transport_simple_get_ops(void) {
    return &simple_ops;
}
