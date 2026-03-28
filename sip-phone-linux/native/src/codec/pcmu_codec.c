#include "pcmu_codec.h"

/*
 * G.711 mu-law encode/decode.
 * ITU-T G.711 — simple lookup table conversion.
 */

#define BIAS    0x84    /* 132 */
#define CLIP    32635

uint8_t pcmu_linear_to_ulaw(int16_t sample) {
    int sign = (sample >> 8) & 0x80;
    if (sign) sample = -sample;
    if (sample > CLIP) sample = CLIP;
    sample += BIAS;

    int exponent = 7;
    for (int mask = 0x4000; (sample & mask) == 0 && exponent > 0; exponent--, mask >>= 1)
        ;

    int mantissa = (sample >> (exponent + 3)) & 0x0F;
    return (uint8_t)(~(sign | (exponent << 4) | mantissa));
}

int16_t pcmu_ulaw_to_linear(uint8_t ulaw) {
    int mu = ~ulaw & 0xFF;
    int sign = mu & 0x80;
    int exponent = (mu >> 4) & 0x07;
    int mantissa = mu & 0x0F;

    int sample = ((mantissa << 3) + BIAS) << exponent;
    sample -= BIAS;

    return (int16_t)(sign ? -sample : sample);
}

int pcmu_encode(const int16_t* pcm_in, uint8_t* ulaw_out, int samples) {
    for (int i = 0; i < samples; i++) {
        ulaw_out[i] = pcmu_linear_to_ulaw(pcm_in[i]);
    }
    return samples;
}

int pcmu_decode(const uint8_t* ulaw_in, int16_t* pcm_out, int bytes) {
    for (int i = 0; i < bytes; i++) {
        pcm_out[i] = pcmu_ulaw_to_linear(ulaw_in[i]);
    }
    return bytes;
}
