#ifndef SIPPHONE_RTCP_HANDLER_H
#define SIPPHONE_RTCP_HANDLER_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define RTCP_PT_SR   200    /* Sender Report */
#define RTCP_PT_RR   201    /* Receiver Report */

/**
 * RTCP Receiver Report block — parsed statistics from remote.
 */
typedef struct {
    uint32_t ssrc_source;       /* SSRC of the source being reported */
    uint8_t  fraction_lost;     /* Fraction of packets lost (0-255, /256) */
    int32_t  cumulative_lost;   /* Total packets lost */
    uint32_t highest_seq;       /* Highest sequence number received */
    uint32_t jitter;            /* Inter-arrival jitter (timestamp units) */
    uint32_t last_sr;           /* Last SR timestamp */
    uint32_t delay_since_sr;    /* Delay since last SR (1/65536 sec) */
} RtcpReportBlock;

/**
 * Network quality metrics derived from RTCP — used for adaptive bitrate.
 */
typedef struct {
    float    packet_loss_percent;   /* 0.0 - 100.0 */
    float    jitter_ms;             /* Inter-arrival jitter in milliseconds */
    float    rtt_ms;                /* Round-trip time in milliseconds */
    uint64_t timestamp_ms;          /* When these stats were computed */
} RtcpQualityMetrics;

/**
 * RTCP session state for tracking statistics.
 */
typedef struct RtcpSession RtcpSession;

/**
 * Callback invoked when quality metrics are updated (every RTCP interval).
 */
typedef void (*rtcp_quality_callback_t)(const RtcpQualityMetrics* metrics, void* user_data);

/**
 * Create an RTCP session.
 * @param local_ssrc   Our SSRC
 * @param sample_rate  Codec sample rate (8000 for NB, 16000 for WB)
 * @param callback     Quality metrics callback
 * @param user_data    Opaque pointer passed to callback
 */
RtcpSession* rtcp_session_create(uint32_t local_ssrc, int sample_rate,
                                  rtcp_quality_callback_t callback,
                                  void* user_data);

void rtcp_session_destroy(RtcpSession* session);

/**
 * Record that we sent an RTP packet (for SR generation).
 */
void rtcp_on_rtp_sent(RtcpSession* session, uint16_t seq, uint32_t timestamp,
                      int payload_bytes);

/**
 * Record that we received an RTP packet (for RR generation).
 */
void rtcp_on_rtp_received(RtcpSession* session, uint16_t seq, uint32_t timestamp);

/**
 * Process an incoming RTCP packet from remote.
 * Parses SR/RR reports and updates quality metrics.
 *
 * @return 0 on success, -1 on parse error
 */
int rtcp_process_incoming(RtcpSession* session, const uint8_t* data, int len);

/**
 * Build an RTCP Receiver Report to send to remote.
 *
 * @param buf       Output buffer
 * @param buf_len   Buffer size
 * @return bytes written, or -1 on error
 */
int rtcp_build_receiver_report(RtcpSession* session, uint8_t* buf, int buf_len);

/**
 * Build an RTCP Sender Report to send to remote.
 *
 * @param buf       Output buffer
 * @param buf_len   Buffer size
 * @return bytes written, or -1 on error
 */
int rtcp_build_sender_report(RtcpSession* session, uint8_t* buf, int buf_len);

/**
 * Get the latest quality metrics.
 */
RtcpQualityMetrics rtcp_get_quality(const RtcpSession* session);

#ifdef __cplusplus
}
#endif

#endif /* SIPPHONE_RTCP_HANDLER_H */
