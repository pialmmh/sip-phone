#ifndef SIPPHONE_RTP_SESSION_H
#define SIPPHONE_RTP_SESSION_H

#include <stdint.h>
#include "rtp_packet.h"
#include "rtcp_handler.h"
#include "jitter_buffer.h"
#include "../amr/amr_codec.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct RtpSession RtpSession;

typedef struct {
    const char*   remote_host;
    int           remote_rtp_port;
    int           remote_rtcp_port;
    int           local_rtp_port;
    int           local_rtcp_port;
    uint32_t      ssrc;
    uint8_t       payload_type;
    AmrCodecType  codec_type;       /* AMR_CODEC_NB or AMR_CODEC_WB */
    int           initial_mode;     /* Initial AMR mode */
    int           dtx;              /* Enable DTX */
    rtcp_quality_callback_t quality_callback;
    void*         quality_user_data;
} RtpSessionConfig;

/**
 * Create and bind an RTP session.
 */
RtpSession* rtp_session_create(const RtpSessionConfig* config);

/**
 * Destroy RTP session and close sockets.
 */
void rtp_session_destroy(RtpSession* session);

/**
 * Send one 20ms PCM frame — encodes to AMR and sends as RTP.
 * @param pcm      PCM samples (160 for NB, 320 for WB)
 * @param cmr      Codec Mode Request to send to remote (-1 = no request)
 * @return 0 on success, -1 on error
 */
int rtp_session_send_frame(RtpSession* session, const int16_t* pcm, int cmr);

/**
 * Receive and decode one RTP packet.
 * Uses jitter buffer internally.
 *
 * @param pcm_out       Output PCM buffer
 * @param cmr_received  [out] CMR value from remote, or -1 if none
 * @return number of PCM samples, or 0 if no frame available
 */
int rtp_session_receive_frame(RtpSession* session, int16_t* pcm_out,
                              int* cmr_received);

/**
 * Process any pending RTCP packets (call periodically).
 */
void rtp_session_process_rtcp(RtpSession* session);

/**
 * Send RTCP reports (call every ~5 seconds).
 */
void rtp_session_send_rtcp(RtpSession* session);

/**
 * Change the encoding AMR mode (for adaptive bitrate).
 */
void rtp_session_set_mode(RtpSession* session, int mode);

/**
 * Get current encoding mode.
 */
int rtp_session_get_mode(const RtpSession* session);

/**
 * Get the latest RTCP quality metrics.
 */
RtcpQualityMetrics rtp_session_get_quality(const RtpSession* session);

#ifdef __cplusplus
}
#endif

#endif /* SIPPHONE_RTP_SESSION_H */
