#include "rtp_packet.h"
#include <string.h>
#include <arpa/inet.h>

int rtp_header_serialize(const RtpHeader* hdr, uint8_t* buf, int buf_len) {
    if (!hdr || !buf || buf_len < RTP_HEADER_SIZE) return -1;

    buf[0] = (uint8_t)((hdr->version << 6) |
                        (hdr->padding << 5) |
                        (hdr->extension << 4) |
                        (hdr->csrc_count & 0x0F));

    buf[1] = (uint8_t)((hdr->marker << 7) |
                        (hdr->payload_type & 0x7F));

    uint16_t seq_net = htons(hdr->sequence);
    memcpy(&buf[2], &seq_net, 2);

    uint32_t ts_net = htonl(hdr->timestamp);
    memcpy(&buf[4], &ts_net, 4);

    uint32_t ssrc_net = htonl(hdr->ssrc);
    memcpy(&buf[8], &ssrc_net, 4);

    return RTP_HEADER_SIZE;
}

int rtp_header_parse(const uint8_t* buf, int buf_len, RtpHeader* hdr) {
    if (!buf || !hdr || buf_len < RTP_HEADER_SIZE) return -1;

    hdr->version    = (buf[0] >> 6) & 0x03;
    hdr->padding    = (buf[0] >> 5) & 0x01;
    hdr->extension  = (buf[0] >> 4) & 0x01;
    hdr->csrc_count = buf[0] & 0x0F;
    hdr->marker     = (buf[1] >> 7) & 0x01;
    hdr->payload_type = buf[1] & 0x7F;

    uint16_t seq_net;
    memcpy(&seq_net, &buf[2], 2);
    hdr->sequence = ntohs(seq_net);

    uint32_t ts_net;
    memcpy(&ts_net, &buf[4], 4);
    hdr->timestamp = ntohl(ts_net);

    uint32_t ssrc_net;
    memcpy(&ssrc_net, &buf[8], 4);
    hdr->ssrc = ntohl(ssrc_net);

    int header_size = RTP_HEADER_SIZE + (hdr->csrc_count * 4);
    if (buf_len < header_size) return -1;

    return header_size;
}
