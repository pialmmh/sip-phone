package com.telcobright.sipphone.phone;

import com.telcobright.sipphone.bus.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adaptive bitrate controller for AMR codec.
 *
 * Subscribes to RtcpStatsEvent on EventBus.
 * Computes target AMR mode based on packet loss and jitter.
 * Publishes AmrModeChangeEvent when mode should change.
 *
 * AMR-NB mode ladder (worst → best):
 *   MR475 (4.75k) → MR515 → MR59 → MR67 → MR74 → MR795 → MR102 → MR122 (12.2k)
 *
 * Smooth stepping: max 2 modes per adjustment to avoid audio artifacts.
 * CMR (Codec Mode Request) in RTP header tells remote to match.
 */
public class AdaptiveBitrateController {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveBitrateController.class);

    /* AMR-NB modes indexed 0-7 */
    private static final int[] NB_MODES = {0, 1, 2, 3, 4, 5, 6, 7};
    private static final String[] NB_LABELS = {
        "4.75k", "5.15k", "5.9k", "6.7k", "7.4k", "7.95k", "10.2k", "12.2k"
    };

    /* Quality thresholds */
    private static final float LOSS_EXCELLENT = 1.0f;
    private static final float LOSS_GOOD = 3.0f;
    private static final float LOSS_FAIR = 5.0f;
    private static final float LOSS_POOR = 10.0f;

    private static final float JITTER_EXCELLENT = 20.0f;
    private static final float JITTER_GOOD = 40.0f;
    private static final float JITTER_FAIR = 60.0f;

    private final EventBus bus;
    private int currentModeIndex = 7;  // Start at best quality (MR122)
    private boolean active = false;

    public AdaptiveBitrateController(EventBus bus) {
        this.bus = bus;
        bus.subscribe(RtcpStatsEvent.class, this::onRtcpStats);
        bus.subscribe(CallEvent.class, this::onCallEvent);
    }

    private void onCallEvent(CallEvent event) {
        switch (event) {
            case CallEvent.Answered a -> {
                active = true;
                currentModeIndex = 7; // Reset to best
                log.info("ABR: active, starting at MR122 (12.2k)");
            }
            case CallEvent.Ended e -> active = false;
            case CallEvent.Failed f -> active = false;
            default -> {}
        }
    }

    private void onRtcpStats(RtcpStatsEvent stats) {
        if (!active) return;

        int targetIndex = computeTargetMode(stats.packetLossPercent(), stats.jitterMs());

        if (targetIndex != currentModeIndex) {
            int oldMode = currentModeIndex;
            currentModeIndex = targetIndex;

            log.info("ABR: mode change {} → {} (loss={}% jitter={}ms)",
                    NB_LABELS[oldMode], NB_LABELS[targetIndex],
                    stats.packetLossPercent(), stats.jitterMs());

            bus.publish(new AmrModeChangeEvent(
                    NB_MODES[targetIndex],
                    NB_LABELS[targetIndex],
                    stats.packetLossPercent(),
                    stats.jitterMs()));
        }
    }

    private int computeTargetMode(float lossPercent, float jitterMs) {
        /* Quality score: 0.0 (terrible) to 1.0 (excellent) */
        float lossScore = lossPercent < LOSS_EXCELLENT ? 1.0f :
                          lossPercent < LOSS_GOOD ? 0.75f :
                          lossPercent < LOSS_FAIR ? 0.5f :
                          lossPercent < LOSS_POOR ? 0.25f : 0.0f;

        float jitterScore = jitterMs < JITTER_EXCELLENT ? 1.0f :
                            jitterMs < JITTER_GOOD ? 0.75f :
                            jitterMs < JITTER_FAIR ? 0.5f : 0.25f;

        /* Combined score, loss-weighted */
        float score = lossScore * 0.7f + jitterScore * 0.3f;

        /* Map to mode index */
        int maxIndex = NB_MODES.length - 1;
        int targetIndex = (int)(score * maxIndex);
        targetIndex = Math.max(0, Math.min(maxIndex, targetIndex));

        /* Smooth: max 2 steps per adjustment */
        if (targetIndex > currentModeIndex) {
            targetIndex = Math.min(targetIndex, currentModeIndex + 2);
        } else if (targetIndex < currentModeIndex) {
            targetIndex = Math.max(targetIndex, currentModeIndex - 2);
        }

        return targetIndex;
    }
}
