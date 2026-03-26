#ifndef SIPPHONE_AMR_RTP_PAYLOAD_H
#define SIPPHONE_AMR_RTP_PAYLOAD_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * AMR RTP payload format (RFC 4867).
 *
 * Bandwidth-efficient format (octet-align=0):
 *   Bits packed tightly, no byte padding between fields.
 *
 *   |CMR (4 bits)|F(1)|FT(4)|Q(1)| frame bits... |padding to byte|
 *
 *   Total header = 10 bits, then frame data bits follow immediately.
 *
 * Octet-aligned format (octet-align=1):
 *   Each field padded to byte boundary.
 *   CMR(4)+pad(4) = 1 byte, F(1)+FT(4)+Q(1)+pad(2) = 1 byte, then frame bytes.
 */

#define AMR_RTP_CMR_NO_REQUEST  15

/* AMR-NB frame sizes in BITS per mode (for bandwidth-efficient packing) */
static const int amr_nb_frame_bits[] = {
     95, /* MR475:  4.75 kbps */
    103, /* MR515:  5.15 kbps */
    118, /* MR59:   5.90 kbps */
    134, /* MR67:   6.70 kbps */
    148, /* MR74:   7.40 kbps */
    159, /* MR795:  7.95 kbps */
    204, /* MR102: 10.2  kbps */
    244, /* MR122: 12.2  kbps */
     39  /* SID */
};

/**
 * Build bandwidth-efficient AMR RTP payload (single frame).
 *
 * @return total payload length in bytes, or -1 on error
 */
int amr_rtp_payload_build_be(int cmr, int frame_type, int quality,
                              const uint8_t* amr_frame, int amr_frame_len,
                              uint8_t* rtp_payload);

/**
 * Parse bandwidth-efficient AMR RTP payload (single frame).
 *
 * @return 0 on success, -1 on error
 */
int amr_rtp_payload_parse_be(const uint8_t* rtp_payload, int payload_len,
                              int* cmr, int* frame_type, int* quality,
                              uint8_t* amr_frame_out, int* amr_frame_len);

/**
 * Build octet-aligned AMR RTP payload (single frame).
 */
int amr_rtp_payload_build_oa(int cmr, int frame_type, int quality,
                              const uint8_t* amr_frame, int amr_frame_len,
                              uint8_t* rtp_payload);

/**
 * Parse octet-aligned AMR RTP payload (single frame).
 */
int amr_rtp_payload_parse_oa(const uint8_t* rtp_payload, int payload_len,
                              int* cmr, int* frame_type, int* quality,
                              const uint8_t** amr_frame, int* amr_frame_len);

/* Legacy names — point to octet-aligned by default */
#define amr_rtp_payload_build amr_rtp_payload_build_oa
#define amr_rtp_payload_parse amr_rtp_payload_parse_oa

#ifdef __cplusplus
}
#endif

#endif /* SIPPHONE_AMR_RTP_PAYLOAD_H */
