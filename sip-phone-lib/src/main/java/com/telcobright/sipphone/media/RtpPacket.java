package com.telcobright.sipphone.media;

import java.nio.ByteBuffer;

/**
 * Simple RTP packet builder/parser for Java-based codecs (PCMU).
 * For AMR, the native layer handles RTP directly.
 */
public class RtpPacket {

    public static final int HEADER_SIZE = 12;
    public static final int VERSION = 2;

    /* Header fields */
    public int version = VERSION;
    public boolean marker;
    public int payloadType;
    public int sequence;
    public long timestamp;
    public long ssrc;

    /* Payload */
    public byte[] payload;
    public int payloadLength;

    /**
     * Serialize to bytes for sending.
     */
    public byte[] toBytes() {
        byte[] packet = new byte[HEADER_SIZE + payloadLength];

        packet[0] = (byte) (version << 6);
        packet[1] = (byte) ((marker ? 0x80 : 0) | (payloadType & 0x7F));

        packet[2] = (byte) (sequence >> 8);
        packet[3] = (byte) sequence;

        packet[4] = (byte) (timestamp >> 24);
        packet[5] = (byte) (timestamp >> 16);
        packet[6] = (byte) (timestamp >> 8);
        packet[7] = (byte) timestamp;

        packet[8] = (byte) (ssrc >> 24);
        packet[9] = (byte) (ssrc >> 16);
        packet[10] = (byte) (ssrc >> 8);
        packet[11] = (byte) ssrc;

        System.arraycopy(payload, 0, packet, HEADER_SIZE, payloadLength);
        return packet;
    }

    /**
     * Parse from received bytes.
     */
    public static RtpPacket parse(byte[] data, int length) {
        if (length < HEADER_SIZE) return null;

        RtpPacket pkt = new RtpPacket();
        pkt.version = (data[0] >> 6) & 0x03;
        pkt.marker = (data[1] & 0x80) != 0;
        pkt.payloadType = data[1] & 0x7F;

        pkt.sequence = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);

        pkt.timestamp = ((long)(data[4] & 0xFF) << 24) | ((long)(data[5] & 0xFF) << 16) |
                         ((long)(data[6] & 0xFF) << 8)  | (data[7] & 0xFF);

        pkt.ssrc = ((long)(data[8] & 0xFF) << 24) | ((long)(data[9] & 0xFF) << 16) |
                    ((long)(data[10] & 0xFF) << 8) | (data[11] & 0xFF);

        int csrcCount = data[0] & 0x0F;
        int headerSize = HEADER_SIZE + csrcCount * 4;
        if (length < headerSize) return null;

        pkt.payloadLength = length - headerSize;
        pkt.payload = new byte[pkt.payloadLength];
        System.arraycopy(data, headerSize, pkt.payload, 0, pkt.payloadLength);

        return pkt;
    }
}
