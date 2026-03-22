package com.telcobright.sipphone.media

import android.util.Log
import kotlinx.coroutines.*

/**
 * Adaptive bitrate controller for AMR codec.
 *
 * Monitors RTCP quality metrics and dynamically switches AMR modes:
 * - Good network  → highest quality (MR122 / WB 23.85k)
 * - Degrading     → step down bitrate
 * - Bad network   → survival mode (MR475 / WB 6.6k)
 *
 * Also handles CMR (Codec Mode Request) from remote side.
 */
class AdaptiveBitrateController(
    private val mediaEngine: NativeMediaEngine,
    private val codecType: Int = NativeMediaEngine.CODEC_TYPE_NB
) : NativeMediaEngine.QualityListener {

    companion object {
        private const val TAG = "AdaptiveBitrate"
        private const val RTCP_INTERVAL_MS = 5000L

        /* AMR-NB mode ladder (worst → best) */
        private val NB_MODES = intArrayOf(
            NativeMediaEngine.NB_MODE_475,  // 4.75 kbps
            NativeMediaEngine.NB_MODE_515,  // 5.15 kbps
            NativeMediaEngine.NB_MODE_59,   // 5.90 kbps
            NativeMediaEngine.NB_MODE_67,   // 6.70 kbps
            NativeMediaEngine.NB_MODE_74,   // 7.40 kbps
            NativeMediaEngine.NB_MODE_795,  // 7.95 kbps
            NativeMediaEngine.NB_MODE_102,  // 10.2 kbps
            NativeMediaEngine.NB_MODE_122   // 12.2 kbps
        )

        /* AMR-WB mode ladder (worst → best) */
        private val WB_MODES = intArrayOf(
            NativeMediaEngine.WB_MODE_660,   //  6.60 kbps
            NativeMediaEngine.WB_MODE_885,   //  8.85 kbps
            NativeMediaEngine.WB_MODE_1265,  // 12.65 kbps
            NativeMediaEngine.WB_MODE_1425,  // 14.25 kbps
            NativeMediaEngine.WB_MODE_1585,  // 15.85 kbps
            NativeMediaEngine.WB_MODE_1825,  // 18.25 kbps
            NativeMediaEngine.WB_MODE_1985,  // 19.85 kbps
            NativeMediaEngine.WB_MODE_2305,  // 23.05 kbps
            NativeMediaEngine.WB_MODE_2385   // 23.85 kbps
        )

        /* Quality thresholds */
        private const val LOSS_EXCELLENT = 1.0f
        private const val LOSS_GOOD = 3.0f
        private const val LOSS_FAIR = 5.0f
        private const val LOSS_POOR = 10.0f

        private const val JITTER_EXCELLENT = 20.0f
        private const val JITTER_GOOD = 40.0f
        private const val JITTER_FAIR = 60.0f
    }

    private val modes get() = if (codecType == NativeMediaEngine.CODEC_TYPE_NB) NB_MODES else WB_MODES
    private var currentModeIndex = modes.size - 1  // Start at best quality
    private var scope: CoroutineScope? = null

    var onModeChanged: ((mode: Int, bitrateLabel: String) -> Unit)? = null
    var onQualityChanged: ((loss: Float, jitter: Float, rtt: Float) -> Unit)? = null

    fun start() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        /* Periodic RTCP report sender */
        scope?.launch {
            while (isActive) {
                delay(RTCP_INTERVAL_MS)
                mediaEngine.nativeSendRtcp()
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
    }

    /**
     * Called from native RTCP quality callback (via JNI).
     */
    override fun onQualityUpdate(packetLossPercent: Float, jitterMs: Float, rttMs: Float) {
        onQualityChanged?.invoke(packetLossPercent, jitterMs, rttMs)

        val targetIndex = computeTargetModeIndex(packetLossPercent, jitterMs)

        if (targetIndex != currentModeIndex) {
            currentModeIndex = targetIndex
            val newMode = modes[currentModeIndex]
            mediaEngine.nativeSetMode(newMode)
            /* Also request remote to match via CMR */
            mediaEngine.nativeSetCmr(newMode)

            val label = getModeLabel(newMode)
            Log.d(TAG, "Mode switched to $label (loss=${packetLossPercent}% jitter=${jitterMs}ms)")
            onModeChanged?.invoke(newMode, label)
        }
    }

    /**
     * Handle CMR (Codec Mode Request) from remote side.
     * Remote is asking us to change our encoding mode.
     */
    fun handleRemoteCmr(cmr: Int) {
        if (cmr < 0 || cmr >= modes.size) return

        val modeIndex = modes.indexOf(cmr)
        if (modeIndex >= 0 && modeIndex != currentModeIndex) {
            currentModeIndex = modeIndex
            mediaEngine.nativeSetMode(cmr)
            Log.d(TAG, "Mode changed by remote CMR to ${getModeLabel(cmr)}")
            onModeChanged?.invoke(cmr, getModeLabel(cmr))
        }
    }

    private fun computeTargetModeIndex(lossPercent: Float, jitterMs: Float): Int {
        val maxIndex = modes.size - 1

        /* Compute quality score 0.0 (terrible) to 1.0 (excellent) */
        val lossScore = when {
            lossPercent < LOSS_EXCELLENT -> 1.0f
            lossPercent < LOSS_GOOD -> 0.75f
            lossPercent < LOSS_FAIR -> 0.5f
            lossPercent < LOSS_POOR -> 0.25f
            else -> 0.0f
        }

        val jitterScore = when {
            jitterMs < JITTER_EXCELLENT -> 1.0f
            jitterMs < JITTER_GOOD -> 0.75f
            jitterMs < JITTER_FAIR -> 0.5f
            else -> 0.25f
        }

        /* Combined score, loss-weighted (loss matters more than jitter) */
        val score = lossScore * 0.7f + jitterScore * 0.3f

        /* Map score to mode index */
        val targetIndex = (score * maxIndex).toInt().coerceIn(0, maxIndex)

        /* Smooth: don't jump more than 2 steps at a time */
        return when {
            targetIndex > currentModeIndex -> minOf(targetIndex, currentModeIndex + 2)
            targetIndex < currentModeIndex -> maxOf(targetIndex, currentModeIndex - 2)
            else -> currentModeIndex
        }
    }

    private fun getModeLabel(mode: Int): String {
        if (codecType == NativeMediaEngine.CODEC_TYPE_NB) {
            return when (mode) {
                0 -> "AMR-NB 4.75k"
                1 -> "AMR-NB 5.15k"
                2 -> "AMR-NB 5.90k"
                3 -> "AMR-NB 6.70k"
                4 -> "AMR-NB 7.40k"
                5 -> "AMR-NB 7.95k"
                6 -> "AMR-NB 10.2k"
                7 -> "AMR-NB 12.2k"
                else -> "AMR-NB ?"
            }
        } else {
            return when (mode) {
                0 -> "AMR-WB 6.60k"
                1 -> "AMR-WB 8.85k"
                2 -> "AMR-WB 12.65k"
                3 -> "AMR-WB 14.25k"
                4 -> "AMR-WB 15.85k"
                5 -> "AMR-WB 18.25k"
                6 -> "AMR-WB 19.85k"
                7 -> "AMR-WB 23.05k"
                8 -> "AMR-WB 23.85k"
                else -> "AMR-WB ?"
            }
        }
    }
}
