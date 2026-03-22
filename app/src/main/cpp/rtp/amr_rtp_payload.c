#include "amr_rtp_payload.h"
#include <string.h>

int amr_rtp_payload_build(int cmr, int frame_type, int quality,
                          const uint8_t* amr_frame, int amr_frame_len,
                          uint8_t* rtp_payload) {
    if (!amr_frame || !rtp_payload || amr_frame_len <= 0) return -1;

    /* CMR header byte: [CMR(4 bits)][Reserved(4 bits)] */
    rtp_payload[0] = (uint8_t)((cmr & 0x0F) << 4);

    /* TOC entry: [F=0(1 bit)][FT(4 bits)][Q(1 bit)][Padding(2 bits)] */
    rtp_payload[1] = (uint8_t)(
        (0 << 7) |                         /* F=0: single frame, no more TOC entries */
        ((frame_type & 0x0F) << 3) |       /* FT: frame type / mode */
        ((quality & 0x01) << 2)            /* Q: quality indicator */
    );

    /* AMR frame data */
    memcpy(&rtp_payload[2], amr_frame, amr_frame_len);

    return amr_frame_len + 2;
}

int amr_rtp_payload_parse(const uint8_t* rtp_payload, int payload_len,
                          int* cmr, int* frame_type, int* quality,
                          const uint8_t** amr_frame, int* amr_frame_len) {
    if (!rtp_payload || payload_len < 2) return -1;

    /* Parse CMR header */
    if (cmr) *cmr = (rtp_payload[0] >> 4) & 0x0F;

    /* Parse TOC entry */
    uint8_t toc = rtp_payload[1];
    /* int f_bit = (toc >> 7) & 0x01; — for multi-frame, unused in single-frame */
    if (frame_type) *frame_type = (toc >> 3) & 0x0F;
    if (quality)    *quality    = (toc >> 2) & 0x01;

    /* AMR frame data starts at byte 2 */
    if (amr_frame)     *amr_frame = &rtp_payload[2];
    if (amr_frame_len) *amr_frame_len = payload_len - 2;

    return 0;
}
