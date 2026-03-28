#ifndef SIPPHONE_PCMU_CODEC_H
#define SIPPHONE_PCMU_CODEC_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define PCMU_SAMPLE_RATE    8000
#define PCMU_FRAME_SAMPLES  160    /* 20ms at 8kHz */
#define PCMU_FRAME_BYTES    160    /* 1 byte per mu-law sample */
#define PCMU_PAYLOAD_TYPE   0

/**
 * Encode one 16-bit linear PCM sample to 8-bit mu-law.
 */
uint8_t pcmu_linear_to_ulaw(int16_t sample);

/**
 * Decode one 8-bit mu-law sample to 16-bit linear PCM.
 */
int16_t pcmu_ulaw_to_linear(uint8_t ulaw);

/**
 * Encode a frame of PCM samples to mu-law.
 * @param pcm_in    Input: 160 int16 samples (20ms at 8kHz)
 * @param ulaw_out  Output: 160 bytes
 * @return number of bytes written (160)
 */
int pcmu_encode(const int16_t* pcm_in, uint8_t* ulaw_out, int samples);

/**
 * Decode a frame of mu-law to PCM samples.
 * @param ulaw_in   Input: 160 bytes
 * @param pcm_out   Output: 160 int16 samples
 * @return number of samples written (160)
 */
int pcmu_decode(const uint8_t* ulaw_in, int16_t* pcm_out, int bytes);

#ifdef __cplusplus
}
#endif

#endif /* SIPPHONE_PCMU_CODEC_H */
