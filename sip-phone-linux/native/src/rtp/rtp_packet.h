#ifndef SIPPHONE_RTP_PACKET_H
#define SIPPHONE_RTP_PACKET_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define RTP_VERSION        2
#define RTP_HEADER_SIZE    12
#define RTP_MAX_PACKET     1500

/**
 * RTP header (RFC 3550).
 */
typedef struct {
    uint8_t  version;       /* 2 */
    uint8_t  padding;
    uint8_t  extension;
    uint8_t  csrc_count;
    uint8_t  marker;
    uint8_t  payload_type;
    uint16_t sequence;
    uint32_t timestamp;
    uint32_t ssrc;
} RtpHeader;

/**
 * Serialize RTP header to network bytes.
 * @return number of bytes written (always 12)
 */
int rtp_header_serialize(const RtpHeader* hdr, uint8_t* buf, int buf_len);

/**
 * Parse RTP header from network bytes.
 * @return header size in bytes, or -1 on error
 */
int rtp_header_parse(const uint8_t* buf, int buf_len, RtpHeader* hdr);

#ifdef __cplusplus
}
#endif

#endif /* SIPPHONE_RTP_PACKET_H */
