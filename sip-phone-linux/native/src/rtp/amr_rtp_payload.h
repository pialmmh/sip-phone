#ifndef SIPPHONE_AMR_RTP_PAYLOAD_H
#define SIPPHONE_AMR_RTP_PAYLOAD_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * AMR RTP payload format (RFC 4867) — octet-aligned mode.
 *
 * Octet-aligned format (simpler, recommended):
 *   +--------+-----+-----+
 *   | CMR    | RES | PAD |   CMR header (1 byte)
 *   +--------+-----+-----+
 *   | F | FT  | Q  | PAD |   TOC entry (1 byte per frame)
 *   +--------+-----+-----+
 *   |   AMR frame data    |   Speech data
 *   +---------------------+
 *
 * CMR = Codec Mode Request (4 bits) — tells remote to switch mode
 * F   = Follow bit (1 = more TOC entries follow)
 * FT  = Frame Type (4 bits) = AMR mode
 * Q   = Quality (1 = good frame)
 */

#define AMR_RTP_CMR_NO_REQUEST  15  /* No mode change requested */

/**
 * Build an AMR RTP payload (octet-aligned, single frame).
 *
 * @param cmr           Codec Mode Request (0-8 for NB, 0-9 for WB, or 15=none)
 * @param frame_type    AMR mode/frame type of the encoded frame
 * @param quality       1 = good frame, 0 = bad/lost
 * @param amr_frame     Encoded AMR frame data
 * @param amr_frame_len Length of AMR frame data
 * @param rtp_payload   Output buffer (must be amr_frame_len + 2)
 * @return total payload length, or -1 on error
 */
int amr_rtp_payload_build(int cmr, int frame_type, int quality,
                          const uint8_t* amr_frame, int amr_frame_len,
                          uint8_t* rtp_payload);

/**
 * Parse an AMR RTP payload (octet-aligned, single frame).
 *
 * @param rtp_payload     Input RTP payload
 * @param payload_len     Length of payload
 * @param cmr             [out] Codec Mode Request from remote
 * @param frame_type      [out] AMR mode/frame type
 * @param quality         [out] Quality bit
 * @param amr_frame       [out] Pointer to AMR frame data within payload
 * @param amr_frame_len   [out] Length of AMR frame data
 * @return 0 on success, -1 on error
 */
int amr_rtp_payload_parse(const uint8_t* rtp_payload, int payload_len,
                          int* cmr, int* frame_type, int* quality,
                          const uint8_t** amr_frame, int* amr_frame_len);

#ifdef __cplusplus
}
#endif

#endif /* SIPPHONE_AMR_RTP_PAYLOAD_H */
