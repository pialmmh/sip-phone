#include "amr_rtp_payload.h"
#include <string.h>

/*
 * Bit-packing helpers for bandwidth-efficient mode.
 */

static int write_bits(uint8_t* buf, int bit_offset, uint32_t value, int nbits) {
    for (int i = nbits - 1; i >= 0; i--) {
        int byte_idx = bit_offset / 8;
        int bit_idx = 7 - (bit_offset % 8);
        if ((value >> i) & 1)
            buf[byte_idx] |= (1 << bit_idx);
        else
            buf[byte_idx] &= ~(1 << bit_idx);
        bit_offset++;
    }
    return bit_offset;
}

static uint32_t read_bits(const uint8_t* buf, int* bit_offset, int nbits) {
    uint32_t value = 0;
    for (int i = 0; i < nbits; i++) {
        int byte_idx = (*bit_offset) / 8;
        int bit_idx = 7 - ((*bit_offset) % 8);
        value = (value << 1) | ((buf[byte_idx] >> bit_idx) & 1);
        (*bit_offset)++;
    }
    return value;
}

static void copy_bits(const uint8_t* src, int src_bit_offset,
                      uint8_t* dst, int dst_bit_offset, int nbits) {
    for (int i = 0; i < nbits; i++) {
        int sb = src_bit_offset + i;
        int db = dst_bit_offset + i;
        int src_byte = sb / 8;
        int src_bit = 7 - (sb % 8);
        int dst_byte = db / 8;
        int dst_bit = 7 - (db % 8);

        if ((src[src_byte] >> src_bit) & 1)
            dst[dst_byte] |= (1 << dst_bit);
        else
            dst[dst_byte] &= ~(1 << dst_bit);
    }
}

/* ================================================================
 * BANDWIDTH-EFFICIENT mode (octet-align=0)
 *
 * Layout: CMR(4) | F(1) | FT(4) | Q(1) | frame_bits | pad to byte
 * Total header = 10 bits
 * ================================================================ */

int amr_rtp_payload_build_be(int cmr, int frame_type, int quality,
                              const uint8_t* amr_frame, int amr_frame_len,
                              uint8_t* rtp_payload) {
    if (!amr_frame || !rtp_payload || frame_type < 0 || frame_type > 8) return -1;

    int frame_bits = amr_nb_frame_bits[frame_type];
    memset(rtp_payload, 0, 64);

    int bit_off = 0;
    bit_off = write_bits(rtp_payload, bit_off, cmr & 0x0F, 4);
    bit_off = write_bits(rtp_payload, bit_off, 0, 1);              /* F=0 */
    bit_off = write_bits(rtp_payload, bit_off, frame_type & 0x0F, 4);
    bit_off = write_bits(rtp_payload, bit_off, quality & 0x01, 1);

    /* Frame data: encoder output is byte-aligned, copy bits */
    copy_bits(amr_frame, 0, rtp_payload, bit_off, frame_bits);
    bit_off += frame_bits;

    return (bit_off + 7) / 8;
}

int amr_rtp_payload_parse_be(const uint8_t* rtp_payload, int payload_len,
                              int* cmr, int* frame_type, int* quality,
                              uint8_t* amr_frame_out, int* amr_frame_len) {
    if (!rtp_payload || payload_len < 2) return -1;

    int bit_off = 0;
    uint32_t cmr_val = read_bits(rtp_payload, &bit_off, 4);
    if (cmr) *cmr = cmr_val;

    read_bits(rtp_payload, &bit_off, 1); /* F bit */

    uint32_t ft_val = read_bits(rtp_payload, &bit_off, 4);
    if (frame_type) *frame_type = ft_val;

    uint32_t q_val = read_bits(rtp_payload, &bit_off, 1);
    if (quality) *quality = q_val;

    if (ft_val > 8) {
        if (amr_frame_len) *amr_frame_len = 0;
        return 0;
    }

    int frame_bits = amr_nb_frame_bits[ft_val];
    int frame_bytes = (frame_bits + 7) / 8;

    if (amr_frame_out) {
        memset(amr_frame_out, 0, frame_bytes);
        copy_bits(rtp_payload, bit_off, amr_frame_out, 0, frame_bits);
    }
    if (amr_frame_len) *amr_frame_len = frame_bytes;

    return 0;
}

/* ================================================================
 * OCTET-ALIGNED mode (octet-align=1)
 * ================================================================ */

int amr_rtp_payload_build_oa(int cmr, int frame_type, int quality,
                              const uint8_t* amr_frame, int amr_frame_len,
                              uint8_t* rtp_payload) {
    if (!amr_frame || !rtp_payload || amr_frame_len <= 0) return -1;

    rtp_payload[0] = (uint8_t)((cmr & 0x0F) << 4);
    rtp_payload[1] = (uint8_t)(
        (0 << 7) |
        ((frame_type & 0x0F) << 3) |
        ((quality & 0x01) << 2)
    );

    memcpy(&rtp_payload[2], amr_frame, amr_frame_len);
    return amr_frame_len + 2;
}

int amr_rtp_payload_parse_oa(const uint8_t* rtp_payload, int payload_len,
                              int* cmr, int* frame_type, int* quality,
                              const uint8_t** amr_frame, int* amr_frame_len) {
    if (!rtp_payload || payload_len < 2) return -1;

    if (cmr) *cmr = (rtp_payload[0] >> 4) & 0x0F;

    uint8_t toc = rtp_payload[1];
    if (frame_type) *frame_type = (toc >> 3) & 0x0F;
    if (quality)    *quality    = (toc >> 2) & 0x01;

    if (amr_frame)     *amr_frame = &rtp_payload[2];
    if (amr_frame_len) *amr_frame_len = payload_len - 2;

    return 0;
}
