#ifndef SIPPHONE_RTP_SESSION_H
#define SIPPHONE_RTP_SESSION_H

#include <stdint.h>
#include "rtp_packet.h"
#include "rtp_transport.h"
#include "rtcp_handler.h"
#include "jitter_buffer.h"
#include "../amr/amr_codec.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Codec type: -1 = PCMU, 0 = AMR-NB, 1 = AMR-WB */
#define CODEC_TYPE_PCMU  (-1)
#define CODEC_TYPE_AMR_NB  AMR_CODEC_NB   /* 0 */
#define CODEC_TYPE_AMR_WB  AMR_CODEC_WB   /* 1 */

typedef struct RtpSession RtpSession;

typedef struct {
    const char*   remote_host;
    int           remote_rtp_port;
    int           remote_rtcp_port;
    int           local_rtp_port;
    int           local_rtcp_port;
    uint32_t      ssrc;
    uint8_t       payload_type;
    int           codec_type;          /* CODEC_TYPE_PCMU, CODEC_TYPE_AMR_NB, CODEC_TYPE_AMR_WB */
    int           initial_mode;        /* AMR mode (ignored for PCMU) */
    int           dtx;
    rtp_quality_cb_t quality_callback;
    void*         quality_user_data;
} RtpSessionConfig;

RtpSession* rtp_session_create(const RtpSessionConfig* config);
void rtp_session_destroy(RtpSession* session);

int rtp_session_send_frame(RtpSession* session, const int16_t* pcm, int cmr);
int rtp_session_receive_frame(RtpSession* session, int16_t* pcm_out, int* cmr_received);

void rtp_session_process_rtcp(RtpSession* session);
void rtp_session_send_rtcp(RtpSession* session);
void rtp_session_set_mode(RtpSession* session, int mode);
int  rtp_session_get_mode(const RtpSession* session);
RtcpQualityMetrics rtp_session_get_quality(const RtpSession* session);

#ifdef __cplusplus
}
#endif

#endif /* SIPPHONE_RTP_SESSION_H */
