#ifndef SIPPHONE_AUDIO_ENGINE_H
#define SIPPHONE_AUDIO_ENGINE_H

#include <oboe/Oboe.h>
#include <atomic>
#include <functional>
#include <mutex>
#include "../rtp/rtp_session.h"

/**
 * Audio engine using Oboe for low-latency audio I/O.
 *
 * Captures mic audio → feeds to RTP session encoder → sends RTP.
 * Receives RTP → decodes via RTP session → plays to speaker.
 */
class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    AudioEngine();
    ~AudioEngine();

    /**
     * Start audio streams.
     * @param rtp_session  Active RTP session for send/receive
     * @param codec_type   AMR_CODEC_NB (8kHz) or AMR_CODEC_WB (16kHz)
     */
    bool start(RtpSession* rtp_session, AmrCodecType codec_type);

    /**
     * Stop audio streams.
     */
    void stop();

    /**
     * Set mute state.
     */
    void setMuted(bool muted);

    /**
     * Set speaker mode (loudspeaker vs earpiece).
     */
    void setSpeakerMode(bool speaker);

    /**
     * Set the CMR (Codec Mode Request) to send with each outgoing RTP packet.
     * Set to -1 to stop requesting mode changes.
     */
    void setCmr(int cmr);

    /**
     * Get the last CMR received from remote.
     */
    int getLastReceivedCmr() const;

    bool isRunning() const { return m_running.load(); }

    /* Oboe callbacks */
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream,
        void* audioData,
        int32_t numFrames) override;

    void onErrorAfterClose(
        oboe::AudioStream* stream,
        oboe::Result error) override;

private:
    void openRecordStream(int sample_rate);
    void openPlaybackStream(int sample_rate);

    std::shared_ptr<oboe::AudioStream> m_record_stream;
    std::shared_ptr<oboe::AudioStream> m_playback_stream;

    RtpSession* m_rtp_session = nullptr;
    AmrCodecType m_codec_type = AMR_CODEC_NB;

    std::atomic<bool> m_running{false};
    std::atomic<bool> m_muted{false};
    std::atomic<int>  m_cmr_to_send{-1};
    std::atomic<int>  m_last_cmr_received{-1};

    /* PCM buffers for frame accumulation (20ms) */
    int16_t m_capture_buffer[320];  /* max: 320 samples for AMR-WB */
    int     m_capture_pos = 0;
    int     m_frame_samples = 160;

    std::mutex m_mutex;
};

#endif /* SIPPHONE_AUDIO_ENGINE_H */
