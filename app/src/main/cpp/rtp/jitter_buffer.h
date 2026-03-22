#ifndef SIPPHONE_JITTER_BUFFER_H
#define SIPPHONE_JITTER_BUFFER_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define JITTER_BUFFER_MAX_PACKETS 50

typedef struct {
    uint8_t  data[256];
    int      data_len;
    uint16_t sequence;
    uint32_t timestamp;
    int      used;
} JitterBufferEntry;

typedef struct {
    JitterBufferEntry entries[JITTER_BUFFER_MAX_PACKETS];
    int      min_delay_ms;      /* Minimum buffering delay */
    int      max_delay_ms;      /* Maximum buffering delay */
    int      current_delay_ms;  /* Current adaptive delay */
    int      sample_rate;
    uint32_t last_played_ts;
    int      started;
} JitterBuffer;

/**
 * Initialize jitter buffer.
 * @param min_delay_ms  Minimum buffer depth (e.g., 20ms = 1 frame)
 * @param max_delay_ms  Maximum buffer depth (e.g., 200ms)
 * @param sample_rate   8000 or 16000
 */
void jitter_buffer_init(JitterBuffer* jb, int min_delay_ms, int max_delay_ms,
                        int sample_rate);

/**
 * Put an incoming RTP packet into the jitter buffer.
 */
void jitter_buffer_put(JitterBuffer* jb, uint16_t seq, uint32_t timestamp,
                       const uint8_t* data, int data_len);

/**
 * Get the next frame to play.
 * @param data      [out] Frame data
 * @param data_len  [out] Frame data length
 * @return 1 if a frame was available, 0 if not (concealment needed)
 */
int jitter_buffer_get(JitterBuffer* jb, uint8_t* data, int* data_len);

/**
 * Reset the jitter buffer.
 */
void jitter_buffer_reset(JitterBuffer* jb);

#ifdef __cplusplus
}
#endif

#endif /* SIPPHONE_JITTER_BUFFER_H */
