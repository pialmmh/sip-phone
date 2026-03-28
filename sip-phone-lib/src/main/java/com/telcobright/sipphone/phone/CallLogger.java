package com.telcobright.sipphone.phone;

import com.telcobright.sipphone.bus.EventBus;
import com.telcobright.sipphone.protocol.SignalingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;

/**
 * Call and RTCP logger — logs events to file with 1-day retention.
 *
 * Logs:
 *   ~/.sipphone/logs/calls-YYYY-MM-DD.log    — call events (trying, answered, ended)
 *   ~/.sipphone/logs/rtcp-YYYY-MM-DD.log     — RTCP quality stats
 *
 * Subscribes to EventBus for all relevant events.
 * Portable — works on Linux, Android (with adjusted log dir), iOS.
 */
public class CallLogger {

    private static final Logger log = LoggerFactory.getLogger(CallLogger.class);
    private static final Path LOG_DIR = Path.of(System.getProperty("user.home"), ".sipphone", "logs");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int RETENTION_DAYS = 7;

    public CallLogger(EventBus bus) {
        try {
            Files.createDirectories(LOG_DIR);
            cleanOldLogs();
        } catch (IOException e) {
            log.error("Failed to create log dir: {}", e.getMessage());
        }

        /* Subscribe to call events */
        bus.subscribe(CallEvent.class, this::onCallEvent);
        bus.subscribe(SignalingResult.class, this::onSignalingResult);
        bus.subscribe(RouteEvent.class, this::onRouteEvent);
        bus.subscribe(RtcpStatsEvent.class, this::onRtcpStats);
    }

    private void onCallEvent(CallEvent event) {
        String line = switch (event) {
            case CallEvent.Trying t -> "TRYING callId=" + t.callId() + " dest=" + t.destination();
            case CallEvent.Ringing r -> "RINGING callId=" + r.callId();
            case CallEvent.Incoming i -> "INCOMING callId=" + i.callId() + " from=" + i.callerNumber();
            case CallEvent.Answered a -> "ANSWERED callId=" + a.callId() + " codec=" + a.codec();
            case CallEvent.MediaStarted m -> "MEDIA callId=" + m.callId() + " codec=" + m.codec();
            case CallEvent.Ended e -> "ENDED callId=" + e.callId() + " reason=" + e.reason();
            case CallEvent.Failed f -> "FAILED callId=" + f.callId() + " reason=" + f.reason();
        };
        writeCallLog(line);
    }

    private void onSignalingResult(SignalingResult result) {
        String line = switch (result) {
            case SignalingResult.Trying t -> "SIG_TRYING callId=" + t.callId();
            case SignalingResult.Progress p -> "SIG_PROGRESS callId=" + p.callId();
            case SignalingResult.Media m -> "SIG_MEDIA callId=" + m.callId() + " sdpLen=" + (m.remoteSdp() != null ? m.remoteSdp().length() : 0);
            case SignalingResult.Answered a -> "SIG_ANSWERED callId=" + a.callId();
            case SignalingResult.Ended e -> "SIG_ENDED callId=" + e.callId() + " reason=" + e.reason();
            case SignalingResult.Failed f -> "SIG_FAILED callId=" + f.callId() + " reason=" + f.reason() + " code=" + f.causeCode();
            case SignalingResult.Incoming i -> "SIG_INCOMING callId=" + i.callId() + " from=" + i.callerNumber();
        };
        writeCallLog(line);
    }

    private void onRouteEvent(RouteEvent event) {
        String line = switch (event) {
            case RouteEvent.Connecting c -> "ROUTE_CONNECTING " + c.routeId();
            case RouteEvent.Registered r -> "ROUTE_REGISTERED " + r.routeId();
            case RouteEvent.Disconnected d -> "ROUTE_DISCONNECTED " + d.routeId() + " reason=" + d.reason();
            case RouteEvent.Reconnecting r -> "ROUTE_RECONNECTING " + r.routeId() + " delay=" + r.delayMs() + "ms";
        };
        writeCallLog(line);
    }

    /**
     * Log RTCP quality stats from event bus.
     */
    private void onRtcpStats(RtcpStatsEvent e) {
        String line = String.format("RTCP loss=%.1f%% jitter=%.1fms rtt=%.1fms",
                e.packetLossPercent(), e.jitterMs(), e.rttMs());
        writeRtcpLog(line);
    }

    private void writeCallLog(String message) {
        writeLine("calls", message);
    }

    private void writeRtcpLog(String message) {
        writeLine("rtcp", message);
    }

    private void writeLine(String prefix, String message) {
        try {
            String today = LocalDateTime.now().format(DATE_FMT);
            String ts = LocalDateTime.now().format(TS_FMT);
            Path file = LOG_DIR.resolve(prefix + "-" + today + ".log");
            String line = ts + " " + message + "\n";
            Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("Log write failed: {}", e.getMessage());
        }
    }

    private void cleanOldLogs() {
        try (Stream<Path> files = Files.list(LOG_DIR)) {
            String cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS).format(DATE_FMT);
            files.filter(p -> p.getFileName().toString().endsWith(".log"))
                 .filter(p -> {
                     String name = p.getFileName().toString();
                     /* Extract date from filename: calls-2026-03-28.log → 2026-03-28 */
                     int dashIdx = name.indexOf('-');
                     if (dashIdx < 0) return false;
                     String dateStr = name.substring(dashIdx + 1, name.length() - 4);
                     return dateStr.compareTo(cutoff) < 0;
                 })
                 .forEach(p -> {
                     try { Files.delete(p); log.debug("Deleted old log: {}", p); }
                     catch (IOException ignored) {}
                 });
        } catch (IOException e) {
            log.debug("Log cleanup failed: {}", e.getMessage());
        }
    }
}
