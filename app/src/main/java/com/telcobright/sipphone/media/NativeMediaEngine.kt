package com.telcobright.sipphone.media

/**
 * JNI bridge to the native RTP/RTCP + AMR codec + Oboe audio engine.
 */
class NativeMediaEngine {

    interface QualityListener {
        fun onQualityUpdate(packetLossPercent: Float, jitterMs: Float, rttMs: Float)
    }

    /**
     * Start media: opens UDP sockets, initializes AMR codec, starts Oboe audio.
     */
    external fun nativeStartMedia(
        remoteHost: String,
        remoteRtpPort: Int,
        remoteRtcpPort: Int,
        localRtpPort: Int,
        localRtcpPort: Int,
        ssrc: Int,
        payloadType: Int,
        codecType: Int,       // 0=AMR-NB, 1=AMR-WB
        initialMode: Int,     // AMR mode index
        dtx: Boolean,
        qualityListener: QualityListener?
    ): Boolean

    /** Stop media: closes sockets, stops audio. */
    external fun nativeStopMedia()

    /** Mute/unmute microphone. */
    external fun nativeSetMuted(muted: Boolean)

    /** Change AMR encoding mode (adaptive bitrate). */
    external fun nativeSetMode(mode: Int)

    /** Set Codec Mode Request to send to remote. */
    external fun nativeSetCmr(cmr: Int)

    /** Get current AMR encoding mode. */
    external fun nativeGetMode(): Int

    /** Send RTCP reports (call every ~5 seconds). */
    external fun nativeSendRtcp()

    companion object {
        const val CODEC_TYPE_NB = 0
        const val CODEC_TYPE_WB = 1

        /* AMR-NB modes */
        const val NB_MODE_475 = 0
        const val NB_MODE_515 = 1
        const val NB_MODE_59 = 2
        const val NB_MODE_67 = 3
        const val NB_MODE_74 = 4
        const val NB_MODE_795 = 5
        const val NB_MODE_102 = 6
        const val NB_MODE_122 = 7

        /* AMR-WB modes */
        const val WB_MODE_660 = 0
        const val WB_MODE_885 = 1
        const val WB_MODE_1265 = 2
        const val WB_MODE_1425 = 3
        const val WB_MODE_1585 = 4
        const val WB_MODE_1825 = 5
        const val WB_MODE_1985 = 6
        const val WB_MODE_2305 = 7
        const val WB_MODE_2385 = 8
    }
}
