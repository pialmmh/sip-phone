#include "amr_codec.h"
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

/* OpenCORE-AMR NB headers (wrapper API) */
#include "interf_enc.h"
#include "interf_dec.h"

/* vo-amrwbenc header (WB encoder wrapper) */
#include "enc_if.h"

/* OpenCORE AMR-WB decoder header (wrapper API) */
#include "dec_if.h"

#define LOG_TAG "AmrCodec"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* AMR-NB frame sizes in bytes per mode (octet-aligned, excluding TOC) */
static const int amr_nb_frame_sizes[AMR_NB_MODE_COUNT] = {
    13, /* 4.75 kbps */
    14, /* 5.15 kbps */
    16, /* 5.90 kbps */
    18, /* 6.70 kbps */
    20, /* 7.40 kbps */
    21, /* 7.95 kbps */
    27, /* 10.2 kbps */
    32, /* 12.2 kbps */
     6  /* SID */
};

/* AMR-WB frame sizes in bytes per mode (octet-aligned, excluding TOC) */
static const int amr_wb_frame_sizes[AMR_WB_MODE_COUNT] = {
    18, /*  6.60 kbps */
    24, /*  8.85 kbps */
    33, /* 12.65 kbps */
    37, /* 14.25 kbps */
    41, /* 15.85 kbps */
    47, /* 18.25 kbps */
    51, /* 19.85 kbps */
    59, /* 23.05 kbps */
    61, /* 23.85 kbps */
     6  /* SID */
};

struct AmrCodec {
    AmrCodecType type;
    void* encoder;
    void* decoder;
    int dtx;
};

AmrCodec* amr_codec_create(AmrCodecType type, int dtx) {
    AmrCodec* codec = (AmrCodec*)calloc(1, sizeof(AmrCodec));
    if (!codec) return NULL;

    codec->type = type;
    codec->dtx = dtx;

    if (type == AMR_CODEC_NB) {
        codec->encoder = Encoder_Interface_init(dtx);
        codec->decoder = Decoder_Interface_init();
    } else {
        /* AMR-WB: use vo-amrwbenc for encode, opencore-amrwb for decode */
        codec->encoder = E_IF_init();
        codec->decoder = D_IF_init();
    }

    if (!codec->encoder || !codec->decoder) {
        LOGE("Failed to initialize AMR %s codec", type == AMR_CODEC_NB ? "NB" : "WB");
        amr_codec_destroy(codec);
        return NULL;
    }

    return codec;
}

void amr_codec_destroy(AmrCodec* codec) {
    if (!codec) return;

    if (codec->type == AMR_CODEC_NB) {
        if (codec->encoder) Encoder_Interface_exit(codec->encoder);
        if (codec->decoder) Decoder_Interface_exit(codec->decoder);
    } else {
        if (codec->encoder) E_IF_exit(codec->encoder);
        if (codec->decoder) D_IF_exit(codec->decoder);
    }

    free(codec);
}

int amr_codec_encode(AmrCodec* codec, int mode,
                     const int16_t* pcm_in, uint8_t* amr_out) {
    if (!codec || !pcm_in || !amr_out) return -1;

    int bytes_written;

    if (codec->type == AMR_CODEC_NB) {
        /* OpenCORE-AMR NB encoder */
        bytes_written = Encoder_Interface_Encode(
            codec->encoder,
            (enum Mode)mode,
            pcm_in,
            amr_out,
            0  /* force_speech: 0 = allow DTX if enabled */
        );
    } else {
        /* vo-amrwbenc WB encoder */
        bytes_written = E_IF_encode(
            codec->encoder,
            mode,
            pcm_in,
            amr_out,
            0
        );
    }

    return bytes_written;
}

int amr_codec_decode(AmrCodec* codec,
                     const uint8_t* amr_in, int amr_len,
                     int16_t* pcm_out) {
    if (!codec || !amr_in || !pcm_out) return -1;

    (void)amr_len; /* frame type is embedded in the TOC byte */

    if (codec->type == AMR_CODEC_NB) {
        Decoder_Interface_Decode(codec->decoder, amr_in, pcm_out, 0);
        return 160;
    } else {
        D_IF_decode(codec->decoder, amr_in, pcm_out, 0);
        return 320;
    }
}

int amr_codec_frame_samples(const AmrCodec* codec) {
    if (!codec) return 0;
    return codec->type == AMR_CODEC_NB ? 160 : 320;
}

int amr_codec_sample_rate(const AmrCodec* codec) {
    if (!codec) return 0;
    return codec->type == AMR_CODEC_NB ? 8000 : 16000;
}

int amr_codec_frame_size(AmrCodecType type, int mode) {
    if (type == AMR_CODEC_NB) {
        if (mode < 0 || mode >= AMR_NB_MODE_COUNT) return -1;
        return amr_nb_frame_sizes[mode];
    } else {
        if (mode < 0 || mode >= AMR_WB_MODE_COUNT) return -1;
        return amr_wb_frame_sizes[mode];
    }
}
