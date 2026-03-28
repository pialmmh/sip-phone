#ifndef SIPPHONE_RTP_TRANSPORT_H
#define SIPPHONE_RTP_TRANSPORT_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * RTP transport abstraction — allows swapping between:
 *   - SIMPLE:  direct UDP send/recv, no jitter buffer (current working impl)
 *   - ORTP:    oRTP library with adaptive jitter buffer + RTCP
 *   - PJMEDIA: pjmedia with advanced jitter buffer + RTCP-FB (future)
 */

typedef enum {
    RTP_TRANSPORT_SIMPLE = 0,
    RTP_TRANSPORT_ORTP   = 1,
    RTP_TRANSPORT_PJMEDIA = 2
} RtpTransportType;

/**
 * RTCP quality metrics — filled by transport layer.
 */
typedef struct {
    float packet_loss_percent;
    float jitter_ms;
    float rtt_ms;
    uint64_t timestamp_ms;
} RtpQualityMetrics;

/**
 * Quality callback — invoked when RTCP metrics are updated.
 */
typedef void (*rtp_quality_cb_t)(const RtpQualityMetrics* metrics, void* user_data);

/**
 * Transport configuration.
 */
typedef struct {
    const char* remote_host;
    int         remote_rtp_port;
    int         remote_rtcp_port;
    int         local_rtp_port;
    int         local_rtcp_port;
    uint32_t    ssrc;
    uint8_t     payload_type;
    int         sample_rate;        /* 8000 or 16000 */
    int         frame_size_ms;      /* 20 */
    rtp_quality_cb_t quality_cb;
    void*       quality_user_data;
} RtpTransportConfig;

/**
 * Opaque transport handle.
 */
typedef struct RtpTransport RtpTransport;

/**
 * Transport operations — vtable for each backend.
 */
typedef struct {
    const char* name;
    RtpTransportType type;

    /** Create and start transport session. */
    RtpTransport* (*create)(const RtpTransportConfig* config);

    /** Destroy transport session. */
    void (*destroy)(RtpTransport* t);

    /**
     * Send one RTP payload (already packed — AMR or raw).
     * @param payload     RTP payload bytes (after RTP header)
     * @param payload_len Payload length
     * @param marker      RTP marker bit
     * @return 0 on success
     */
    int (*send_payload)(RtpTransport* t, const uint8_t* payload, int payload_len, int marker);

    /**
     * Receive one RTP payload.
     * @param payload     [out] Buffer for payload
     * @param max_len     Buffer size
     * @return payload length, or 0 if none available
     */
    int (*recv_payload)(RtpTransport* t, uint8_t* payload, int max_len);

    /** Send RTCP reports. */
    void (*send_rtcp)(RtpTransport* t);

    /** Get latest quality metrics. */
    RtpQualityMetrics (*get_quality)(const RtpTransport* t);

} RtpTransportOps;

/**
 * Get transport operations for a given backend type.
 * Returns NULL if backend not compiled in.
 */
const RtpTransportOps* rtp_transport_get_ops(RtpTransportType type);

/**
 * List available transport backends (null-terminated array).
 */
const RtpTransportOps** rtp_transport_list_available(void);

#ifdef __cplusplus
}
#endif

#endif /* SIPPHONE_RTP_TRANSPORT_H */
