#ifndef SIPPHONE_AMR_CODEC_H
#define SIPPHONE_AMR_CODEC_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * AMR-NB modes (bitrates) — used for adaptive bitrate switching.
 * Values match the CMR field in RFC 4867 RTP payload.
 */
typedef enum {
    AMR_NB_MODE_475  = 0,   /* 4.75 kbps - survival mode */
    AMR_NB_MODE_515  = 1,   /* 5.15 kbps */
    AMR_NB_MODE_59   = 2,   /* 5.90 kbps */
    AMR_NB_MODE_67   = 3,   /* 6.70 kbps */
    AMR_NB_MODE_74   = 4,   /* 7.40 kbps */
    AMR_NB_MODE_795  = 5,   /* 7.95 kbps */
    AMR_NB_MODE_102  = 6,   /* 10.2 kbps */
    AMR_NB_MODE_122  = 7,   /* 12.2 kbps - best quality */
    AMR_NB_MODE_SID  = 8,   /* silence descriptor (DTX) */
    AMR_NB_MODE_COUNT = 9
} AmrNbMode;

/**
 * AMR-WB modes (bitrates).
 */
typedef enum {
    AMR_WB_MODE_660  = 0,   /*  6.60 kbps */
    AMR_WB_MODE_885  = 1,   /*  8.85 kbps */
    AMR_WB_MODE_1265 = 2,   /* 12.65 kbps */
    AMR_WB_MODE_1425 = 3,   /* 14.25 kbps */
    AMR_WB_MODE_1585 = 4,   /* 15.85 kbps */
    AMR_WB_MODE_1825 = 5,   /* 18.25 kbps */
    AMR_WB_MODE_1985 = 6,   /* 19.85 kbps */
    AMR_WB_MODE_2305 = 7,   /* 23.05 kbps */
    AMR_WB_MODE_2385 = 8,   /* 23.85 kbps - best quality */
    AMR_WB_MODE_SID  = 9,   /* silence descriptor (DTX) */
    AMR_WB_MODE_COUNT = 10
} AmrWbMode;

typedef enum {
    AMR_CODEC_NB = 0,       /* Narrowband: 8kHz, 160 samples/frame */
    AMR_CODEC_WB = 1        /* Wideband:  16kHz, 320 samples/frame */
} AmrCodecType;

/**
 * AMR codec context — holds encoder and decoder state.
 */
typedef struct AmrCodec AmrCodec;

/**
 * Create an AMR codec instance.
 * @param type  AMR_CODEC_NB or AMR_CODEC_WB
 * @param dtx   Enable discontinuous transmission (1=on, 0=off)
 * @return codec context, or NULL on failure
 */
AmrCodec* amr_codec_create(AmrCodecType type, int dtx);

/**
 * Destroy an AMR codec instance and free resources.
 */
void amr_codec_destroy(AmrCodec* codec);

/**
 * Encode one 20ms frame of PCM audio to AMR.
 *
 * @param codec      Codec context
 * @param mode       Encoding mode (bitrate) — AMR_NB_MODE_* or AMR_WB_MODE_*
 * @param pcm_in     Input PCM samples: 160 (NB) or 320 (WB) int16 samples
 * @param amr_out    Output buffer (max 64 bytes is safe)
 * @return number of bytes written to amr_out, or -1 on error
 */
int amr_codec_encode(AmrCodec* codec, int mode,
                     const int16_t* pcm_in, uint8_t* amr_out);

/**
 * Decode one AMR frame to 20ms of PCM audio.
 *
 * @param codec      Codec context
 * @param amr_in     Input AMR frame bytes
 * @param amr_len    Length of amr_in
 * @param pcm_out    Output buffer: 160 (NB) or 320 (WB) int16 samples
 * @return number of PCM samples written, or -1 on error
 */
int amr_codec_decode(AmrCodec* codec,
                     const uint8_t* amr_in, int amr_len,
                     int16_t* pcm_out);

/**
 * Get the frame size in PCM samples for this codec type.
 */
int amr_codec_frame_samples(const AmrCodec* codec);

/**
 * Get the sample rate for this codec type.
 */
int amr_codec_sample_rate(const AmrCodec* codec);

/**
 * Get expected AMR frame size in bytes for a given mode.
 */
int amr_codec_frame_size(AmrCodecType type, int mode);

#ifdef __cplusplus
}
#endif

#endif /* SIPPHONE_AMR_CODEC_H */
