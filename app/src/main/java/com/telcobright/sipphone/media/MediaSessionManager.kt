package com.telcobright.sipphone.media

import android.util.Log
import com.telcobright.sipphone.verto.SdpBuilder
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.random.Random

/**
 * Manages the media session lifecycle — coordinates between
 * Verto signaling (SDP exchange) and native media engine (RTP/RTCP + AMR/PCMU).
 */
class MediaSessionManager {

    companion object {
        private const val TAG = "MediaSession"
        private const val RTP_PORT_RANGE_START = 10000
        private const val RTP_PORT_RANGE_END = 20000
    }

    private val mediaEngine = NativeMediaEngine()
    private var adaptiveController: AdaptiveBitrateController? = null

    var localRtpPort: Int = 0
        private set
    var localRtcpPort: Int = 0
        private set
    var localIp: String = "0.0.0.0"
        private set
    var currentCodec: String = SdpBuilder.CODEC_PCMU
        private set
    var isActive: Boolean = false
        private set

    var onModeChanged: ((mode: Int, label: String) -> Unit)? = null
    var onQualityChanged: ((loss: Float, jitter: Float, rtt: Float) -> Unit)? = null

    init {
        allocateLocalPorts()
        localIp = detectLocalIp()
    }

    /**
     * Create SDP offer.
     */
    fun createOffer(
        preferWideband: Boolean = false,
        codec: String = SdpBuilder.CODEC_PCMU
    ): String {
        currentCodec = codec
        return SdpBuilder.buildOffer(localIp, localRtpPort, codec)
    }

    /**
     * Create SDP answer for inbound call.
     */
    fun createAnswer(remoteSdp: String, preferredCodec: String = SdpBuilder.CODEC_PCMU): String {
        val parsed = SdpBuilder.parseRemoteSdp(remoteSdp)
        if (parsed != null) {
            currentCodec = parsed.codecName
        }
        return SdpBuilder.buildAnswer(localIp, localRtpPort, remoteSdp, preferredCodec)
    }

    /**
     * Start media based on remote SDP.
     */
    fun startMedia(remoteSdp: String): Boolean {
        val mediaInfo = SdpBuilder.parseRemoteSdp(remoteSdp)
        if (mediaInfo == null) {
            Log.e(TAG, "Failed to parse remote SDP")
            return false
        }

        currentCodec = mediaInfo.codecName

        if (mediaInfo.codecName == SdpBuilder.CODEC_PCMU) {
            return startPcmuMedia(mediaInfo)
        } else {
            return startAmrMedia(mediaInfo)
        }
    }

    private fun startAmrMedia(mediaInfo: SdpBuilder.SdpMediaInfo): Boolean {
        val codecType = mediaInfo.codecType
        val initialMode = if (codecType == NativeMediaEngine.CODEC_TYPE_WB)
            NativeMediaEngine.WB_MODE_2385 else NativeMediaEngine.NB_MODE_122

        val ssrc = Random.nextInt()

        val started = mediaEngine.nativeStartMedia(
            remoteHost = mediaInfo.remoteIp,
            remoteRtpPort = mediaInfo.remoteRtpPort,
            remoteRtcpPort = mediaInfo.remoteRtcpPort,
            localRtpPort = localRtpPort,
            localRtcpPort = localRtcpPort,
            ssrc = ssrc,
            payloadType = mediaInfo.payloadType,
            codecType = codecType,
            initialMode = initialMode,
            dtx = false,
            qualityListener = null
        )

        if (!started) {
            Log.e(TAG, "Failed to start AMR media")
            return false
        }

        adaptiveController = AdaptiveBitrateController(mediaEngine, codecType).apply {
            this.onModeChanged = this@MediaSessionManager.onModeChanged
            this.onQualityChanged = this@MediaSessionManager.onQualityChanged
            start()
        }

        isActive = true
        Log.d(TAG, "AMR media started: ${mediaInfo.remoteIp}:${mediaInfo.remoteRtpPort}")
        return true
    }

    private fun startPcmuMedia(mediaInfo: SdpBuilder.SdpMediaInfo): Boolean {
        /*
         * PCMU (G.711 mu-law): For initial testing, we use Android's built-in
         * AudioRecord/AudioTrack with simple RTP. PCMU is trivial — each 16-bit
         * PCM sample maps to one 8-bit mu-law byte via a lookup table.
         *
         * TODO: Implement PCMU RTP sender/receiver in native code.
         * For now, log the media parameters for manual testing.
         */
        Log.i(TAG, "PCMU media: remote=${mediaInfo.remoteIp}:${mediaInfo.remoteRtpPort} " +
                   "PT=${mediaInfo.payloadType} local=$localRtpPort")

        /* Use native engine with PCMU codec type (-1 signals PCMU) */
        val ssrc = Random.nextInt()
        val started = mediaEngine.nativeStartMedia(
            remoteHost = mediaInfo.remoteIp,
            remoteRtpPort = mediaInfo.remoteRtpPort,
            remoteRtcpPort = mediaInfo.remoteRtcpPort,
            localRtpPort = localRtpPort,
            localRtcpPort = localRtcpPort,
            ssrc = ssrc,
            payloadType = mediaInfo.payloadType,
            codecType = -1, // PCMU
            initialMode = 0,
            dtx = false,
            qualityListener = null
        )

        isActive = started
        return started
    }

    fun stopMedia() {
        adaptiveController?.stop()
        adaptiveController = null

        if (isActive) {
            mediaEngine.nativeStopMedia()
            isActive = false
            Log.d(TAG, "Media stopped")
        }
    }

    fun setMuted(muted: Boolean) {
        if (isActive) mediaEngine.nativeSetMuted(muted)
    }

    private fun allocateLocalPorts() {
        for (i in 0..50) {
            val port = RTP_PORT_RANGE_START +
                (Random.nextInt(RTP_PORT_RANGE_END - RTP_PORT_RANGE_START) and 0xFFFE)
            try {
                DatagramSocket(port).close()
                DatagramSocket(port + 1).close()
                localRtpPort = port
                localRtcpPort = port + 1
                return
            } catch (_: Exception) { continue }
        }
        localRtpPort = 10000
        localRtcpPort = 10001
    }

    private fun detectLocalIp(): String {
        return try {
            DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByName("8.8.8.8"), 10002)
                socket.localAddress.hostAddress ?: "0.0.0.0"
            }
        } catch (_: Exception) { "0.0.0.0" }
    }
}
