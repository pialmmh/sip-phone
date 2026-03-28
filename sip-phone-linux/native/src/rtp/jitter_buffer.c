#include "jitter_buffer.h"
#include <string.h>

void jitter_buffer_init(JitterBuffer* jb, int min_delay_ms, int max_delay_ms,
                        int sample_rate) {
    memset(jb, 0, sizeof(JitterBuffer));
    jb->min_delay_ms = min_delay_ms;
    jb->max_delay_ms = max_delay_ms;
    jb->current_delay_ms = min_delay_ms;
    jb->sample_rate = sample_rate;
    jb->started = 0;
}

void jitter_buffer_put(JitterBuffer* jb, uint16_t seq, uint32_t timestamp,
                       const uint8_t* data, int data_len) {
    if (!jb || !data || data_len <= 0 || data_len > 256) return;

    int slot = seq % JITTER_BUFFER_MAX_PACKETS;
    JitterBufferEntry* entry = &jb->entries[slot];

    entry->sequence = seq;
    entry->timestamp = timestamp;
    entry->data_len = data_len;
    memcpy(entry->data, data, data_len);
    entry->used = 0;

    if (!jb->started) {
        /* Start playback after buffering min_delay worth of frames */
        int buffer_frames = (jb->min_delay_ms / 20);
        if (buffer_frames < 1) buffer_frames = 1;
        jb->last_played_ts = seq - buffer_frames;
        jb->started = 1;
    }
}

int jitter_buffer_get(JitterBuffer* jb, uint8_t* data, int* data_len) {
    if (!jb || !data || !data_len) return 0;

    /* Advance play cursor by one sequence number */
    uint16_t target_seq = (uint16_t)(jb->last_played_ts + 1);

    /* Search for matching frame by sequence number */
    int slot = target_seq % JITTER_BUFFER_MAX_PACKETS;
    JitterBufferEntry* entry = &jb->entries[slot];

    if (entry->data_len > 0 && !entry->used && entry->sequence == target_seq) {
        memcpy(data, entry->data, entry->data_len);
        *data_len = entry->data_len;
        entry->used = 1;
        jb->last_played_ts = target_seq;
        return 1;
    }

    /* No frame found — advance cursor, return silence */
    jb->last_played_ts = target_seq;
    *data_len = 0;
    return 0;
}

void jitter_buffer_reset(JitterBuffer* jb) {
    if (!jb) return;
    memset(jb->entries, 0, sizeof(jb->entries));
    jb->started = 0;
}
