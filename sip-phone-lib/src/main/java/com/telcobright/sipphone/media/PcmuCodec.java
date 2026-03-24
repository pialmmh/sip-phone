package com.telcobright.sipphone.media;

/**
 * G.711 mu-law (PCMU) codec.
 * Converts between 16-bit linear PCM and 8-bit mu-law.
 *
 * PCMU: 8kHz sample rate, 8 bits per sample, 64 kbps.
 * One 20ms frame = 160 samples = 160 bytes (mu-law) = 320 bytes (PCM).
 */
public final class PcmuCodec {

    public static final int SAMPLE_RATE = 8000;
    public static final int FRAME_SAMPLES = 160;    // 20ms at 8kHz
    public static final int FRAME_BYTES = 160;       // 1 byte per mu-law sample
    public static final int PAYLOAD_TYPE = 0;        // Static PT for PCMU

    private static final int BIAS = 0x84;   // 132
    private static final int MAX = 32635;   // 32767 - BIAS
    private static final int CLIP = 32635;

    private PcmuCodec() {}

    /**
     * Encode one 16-bit linear PCM sample to 8-bit mu-law.
     */
    public static byte linearToMuLaw(short sample) {
        int sign = (sample >> 8) & 0x80;
        if (sign != 0) sample = (short) -sample;
        if (sample > CLIP) sample = CLIP;
        sample = (short) (sample + BIAS);

        int exponent = 7;
        for (int expMask = 0x4000; (sample & expMask) == 0 && exponent > 0; exponent--, expMask >>= 1) {
        }

        int mantissa = (sample >> (exponent + 3)) & 0x0F;
        byte muLawByte = (byte) ~(sign | (exponent << 4) | mantissa);
        return muLawByte;
    }

    /**
     * Decode one 8-bit mu-law sample to 16-bit linear PCM.
     */
    public static short muLawToLinear(byte muLawByte) {
        int mu = ~muLawByte & 0xFF;
        int sign = mu & 0x80;
        int exponent = (mu >> 4) & 0x07;
        int mantissa = mu & 0x0F;

        int sample = ((mantissa << 3) + BIAS) << exponent;
        sample -= BIAS;

        return (short) (sign != 0 ? -sample : sample);
    }

    /**
     * Encode a PCM frame (160 samples) to mu-law (160 bytes).
     */
    public static byte[] encode(short[] pcm, int offset, int length) {
        byte[] muLaw = new byte[length];
        for (int i = 0; i < length; i++) {
            muLaw[i] = linearToMuLaw(pcm[offset + i]);
        }
        return muLaw;
    }

    /**
     * Decode a mu-law frame (160 bytes) to PCM (160 samples).
     */
    public static short[] decode(byte[] muLaw, int offset, int length) {
        short[] pcm = new short[length];
        for (int i = 0; i < length; i++) {
            pcm[i] = muLawToLinear(muLaw[offset + i]);
        }
        return pcm;
    }
}
