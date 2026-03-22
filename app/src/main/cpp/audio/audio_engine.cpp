#include "audio_engine.h"
#include <android/log.h>
#include <cstring>

#define LOG_TAG "AudioEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

AudioEngine::AudioEngine() = default;

AudioEngine::~AudioEngine() {
    stop();
}

bool AudioEngine::start(RtpSession* rtp_session, AmrCodecType codec_type) {
    std::lock_guard<std::mutex> lock(m_mutex);

    if (m_running.load()) {
        LOGD("Audio engine already running");
        return true;
    }

    m_rtp_session = rtp_session;
    m_codec_type = codec_type;
    m_frame_samples = (codec_type == AMR_CODEC_NB) ? 160 : 320;
    m_capture_pos = 0;

    int sample_rate = (codec_type == AMR_CODEC_NB) ? 8000 : 16000;

    openRecordStream(sample_rate);
    openPlaybackStream(sample_rate);

    if (m_record_stream) {
        m_record_stream->requestStart();
    }
    if (m_playback_stream) {
        m_playback_stream->requestStart();
    }

    m_running.store(true);
    LOGD("Audio engine started: %dHz, frame=%d samples",
         sample_rate, m_frame_samples);
    return true;
}

void AudioEngine::stop() {
    std::lock_guard<std::mutex> lock(m_mutex);

    m_running.store(false);

    if (m_record_stream) {
        m_record_stream->requestStop();
        m_record_stream->close();
        m_record_stream.reset();
    }
    if (m_playback_stream) {
        m_playback_stream->requestStop();
        m_playback_stream->close();
        m_playback_stream.reset();
    }

    m_rtp_session = nullptr;
    LOGD("Audio engine stopped");
}

void AudioEngine::setMuted(bool muted) {
    m_muted.store(muted);
}

void AudioEngine::setSpeakerMode(bool speaker) {
    /* Oboe doesn't directly control speaker routing.
       On Android, use AudioManager.setSpeakerphoneOn() from Java side. */
    (void)speaker;
}

void AudioEngine::setCmr(int cmr) {
    m_cmr_to_send.store(cmr);
}

int AudioEngine::getLastReceivedCmr() const {
    return m_last_cmr_received.load();
}

oboe::DataCallbackResult AudioEngine::onAudioReady(
    oboe::AudioStream* stream,
    void* audioData,
    int32_t numFrames) {

    if (!m_running.load() || !m_rtp_session) {
        return oboe::DataCallbackResult::Stop;
    }

    auto* samples = static_cast<int16_t*>(audioData);

    if (stream->getDirection() == oboe::Direction::Input) {
        /* === CAPTURE PATH === */
        if (m_muted.load()) {
            /* Don't send audio when muted, but still accumulate to keep timing */
            return oboe::DataCallbackResult::Continue;
        }

        /* Accumulate samples until we have a full 20ms frame */
        int remaining = numFrames;
        int offset = 0;

        while (remaining > 0) {
            int need = m_frame_samples - m_capture_pos;
            int copy = (remaining < need) ? remaining : need;

            memcpy(&m_capture_buffer[m_capture_pos], &samples[offset],
                   copy * sizeof(int16_t));
            m_capture_pos += copy;
            offset += copy;
            remaining -= copy;

            if (m_capture_pos >= m_frame_samples) {
                /* Full frame — encode and send */
                int cmr = m_cmr_to_send.load();
                rtp_session_send_frame(m_rtp_session, m_capture_buffer, cmr);
                m_capture_pos = 0;
            }
        }
    } else {
        /* === PLAYBACK PATH === */
        int produced = 0;

        while (produced < numFrames) {
            int16_t pcm_frame[320];
            int cmr_received = -1;

            int decoded = rtp_session_receive_frame(m_rtp_session, pcm_frame,
                                                     &cmr_received);

            if (cmr_received >= 0) {
                m_last_cmr_received.store(cmr_received);
            }

            /* Process RTCP while we're at it */
            rtp_session_process_rtcp(m_rtp_session);

            if (decoded <= 0) {
                /* Fill remaining with silence */
                memset(&samples[produced], 0,
                       (numFrames - produced) * sizeof(int16_t));
                break;
            }

            int copy = (decoded < (numFrames - produced)) ?
                       decoded : (numFrames - produced);
            memcpy(&samples[produced], pcm_frame, copy * sizeof(int16_t));
            produced += copy;
        }
    }

    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream* stream,
                                     oboe::Result error) {
    LOGE("Audio stream error: %s (direction=%d)",
         oboe::convertToText(error),
         static_cast<int>(stream->getDirection()));

    /* Attempt restart */
    if (m_running.load()) {
        int sample_rate = (m_codec_type == AMR_CODEC_NB) ? 8000 : 16000;
        if (stream->getDirection() == oboe::Direction::Input) {
            openRecordStream(sample_rate);
            if (m_record_stream) m_record_stream->requestStart();
        } else {
            openPlaybackStream(sample_rate);
            if (m_playback_stream) m_playback_stream->requestStart();
        }
    }
}

void AudioEngine::openRecordStream(int sample_rate) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setFormat(oboe::AudioFormat::I16)
           ->setChannelCount(oboe::ChannelCount::Mono)
           ->setSampleRate(sample_rate)
           ->setInputPreset(oboe::InputPreset::VoiceCommunication)
           ->setDataCallback(this)
           ->setErrorCallback(this);

    oboe::Result result = builder.openStream(m_record_stream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open record stream: %s", oboe::convertToText(result));
        m_record_stream.reset();
    }
}

void AudioEngine::openPlaybackStream(int sample_rate) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setFormat(oboe::AudioFormat::I16)
           ->setChannelCount(oboe::ChannelCount::Mono)
           ->setSampleRate(sample_rate)
           ->setUsage(oboe::Usage::VoiceCommunication)
           ->setDataCallback(this)
           ->setErrorCallback(this);

    oboe::Result result = builder.openStream(m_playback_stream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open playback stream: %s", oboe::convertToText(result));
        m_playback_stream.reset();
    }
}
