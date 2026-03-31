package com.telcobright.sipphone.phone.call;

import com.telcobright.sipphone.bus.EventBus;
import com.telcobright.sipphone.phone.*;
import com.telcobright.sipphone.protocol.SignalingBridge;
import com.telcobright.sipphone.statemachine.FluentBuilder;
import com.telcobright.sipphone.statemachine.GenericStateMachine;
import com.telcobright.sipphone.verto.SdpBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Creates call leg state machines using FluentBuilder.
 *
 * States: IDLE → TRYING → PROGRESS → ANSWERED → COMPLETED
 *                                              → FAILED
 *
 * Each call leg is one GenericStateMachine instance.
 * The CallMachineRegistry manages multiple legs by callId.
 */
public class CallMachineFactory {

    private static final Logger log = LoggerFactory.getLogger(CallMachineFactory.class);

    private static final long TRYING_TIMEOUT_SEC = 30;
    private static final long RINGING_TIMEOUT_SEC = 60;
    private static final long MAX_CALL_DURATION_SEC = 3600;

    public GenericStateMachine<CallContext, CallRuntimeContext> createMachine(String machineId) {

        return FluentBuilder.<CallContext, CallRuntimeContext>create(machineId)
            .initialState("IDLE")

            // ==================== IDLE ====================
            .state("IDLE")
            .done()

            // ==================== TRYING ====================
            .state("TRYING")
                .onEntry(machine -> {
                    CallContext ctx = machine.getPersistingEntity();
                    CallRuntimeContext rt = machine.getContext();
                    String callId = machine.getId();

                    log.info("[Call-{}] TRYING dest={} codec={}", callId, ctx.getDestination(), ctx.getCodec());

                    // Get signaling bridge from route
                    SignalingBridge bridge = rt.getCallRouter().getBridgeForRoute(rt.getRouteId());
                    if (bridge == null) {
                        log.error("[Call-{}] No bridge available", callId);
                        ctx.setEndReason("No route available");
                        machine.transitionTo("FAILED");
                        return;
                    }
                    bridge.init(rt.getBus());
                    rt.setBridge(bridge);

                    // Build SDP and send invite
                    String sdp = SdpBuilder.buildOffer(rt.getLocalIp(), rt.getLocalRtpPort(), ctx.getCodec());
                    ctx.setLocalSdp(sdp);
                    ctx.setStartTimeMs(System.currentTimeMillis());

                    String remoteCallId = bridge.sendInvite(ctx.getDestination(), sdp);
                    ctx.setCallId(remoteCallId);

                    rt.getBus().publish(new CallEvent.Trying(remoteCallId, ctx.getDestination()));
                })
                .stay(CallMachineEvent.class, (machine, rawEvent) -> {
                    CallMachineEvent event = (CallMachineEvent) rawEvent;
                    CallContext ctx = machine.getPersistingEntity();

                    switch (event.getType()) {
                        case MEDIA -> {
                            ctx.setPendingSdp(event.getSdp());
                            machine.transitionTo("PROGRESS");
                        }
                        case ANSWERED -> {
                            String sdp = event.getSdp().isEmpty() ? ctx.getPendingSdp() : event.getSdp();
                            ctx.setRemoteSdp(sdp);
                            machine.transitionTo("ANSWERED");
                        }
                        case ENDED -> {
                            ctx.setEndReason(event.getReason());
                            machine.transitionTo("COMPLETED");
                        }
                        case FAILED -> {
                            ctx.setEndReason(event.getReason());
                            machine.transitionTo("FAILED");
                        }
                        case BYE -> {
                            CallRuntimeContext rt = machine.getContext();
                            if (rt.getBridge() != null) rt.getBridge().sendBye(ctx.getCallId());
                            ctx.setEndReason("User hangup");
                            machine.transitionTo("COMPLETED");
                        }
                        default -> {}
                    }
                })
                .timeout(TRYING_TIMEOUT_SEC, TimeUnit.SECONDS, "FAILED")
            .done()

            // ==================== PROGRESS (ringing) ====================
            .state("PROGRESS")
                .onEntry(machine -> {
                    log.info("[Call-{}] PROGRESS (ringing)", machine.getId());
                    machine.getContext().getBus().publish(new CallEvent.Ringing(machine.getPersistingEntity().getCallId()));
                })
                .stay(CallMachineEvent.class, (machine, rawEvent) -> {
                    CallMachineEvent event = (CallMachineEvent) rawEvent;
                    CallContext ctx = machine.getPersistingEntity();

                    switch (event.getType()) {
                        case MEDIA -> ctx.setPendingSdp(event.getSdp());
                        case ANSWERED -> {
                            String sdp = event.getSdp().isEmpty() ? ctx.getPendingSdp() : event.getSdp();
                            ctx.setRemoteSdp(sdp);
                            machine.transitionTo("ANSWERED");
                        }
                        case ENDED -> { ctx.setEndReason(event.getReason()); machine.transitionTo("COMPLETED"); }
                        case FAILED -> { ctx.setEndReason(event.getReason()); machine.transitionTo("FAILED"); }
                        case BYE -> {
                            CallRuntimeContext rt = machine.getContext();
                            if (rt.getBridge() != null) rt.getBridge().sendBye(ctx.getCallId());
                            ctx.setEndReason("User hangup");
                            machine.transitionTo("COMPLETED");
                        }
                        default -> {}
                    }
                })
                .timeout(RINGING_TIMEOUT_SEC, TimeUnit.SECONDS, "FAILED")
            .done()

            // ==================== ANSWERED ====================
            .state("ANSWERED")
                .onEntry(machine -> {
                    CallContext ctx = machine.getPersistingEntity();
                    CallRuntimeContext rt = machine.getContext();

                    ctx.setAnswerTimeMs(System.currentTimeMillis());
                    log.info("[Call-{}] ANSWERED", machine.getId());

                    // Start media
                    String sdp = ctx.getRemoteSdp();
                    SdpBuilder.SdpMediaInfo info = SdpBuilder.parseRemoteSdp(sdp);
                    if (info != null) {
                        ctx.setCodec(info.codecName());
                        if (rt.getMediaHandler() != null) {
                            rt.getMediaHandler().startMedia(info.remoteIp(), info.remoteRtpPort(),
                                    info.remoteRtcpPort(), rt.getLocalRtpPort(),
                                    info.payloadType(), info.codecType(), info.codecName());
                        }
                        rt.getBus().publish(new CallEvent.MediaStarted(ctx.getCallId(), info.codecName()));
                    }

                    rt.getBus().publish(new CallEvent.Answered(ctx.getCallId(), ctx.getCodec()));
                })
                .onExit(machine -> {
                    // Stop media on exit
                    CallRuntimeContext rt = machine.getContext();
                    if (rt.getMediaHandler() != null) rt.getMediaHandler().stopMedia();
                })
                .stay(CallMachineEvent.class, (machine, rawEvent) -> {
                    CallMachineEvent event = (CallMachineEvent) rawEvent;
                    CallContext ctx = machine.getPersistingEntity();
                    CallRuntimeContext rt = machine.getContext();

                    switch (event.getType()) {
                        case MEDIA -> {
                            // Mid-call SDP update
                            ctx.setRemoteSdp(event.getSdp());
                            if (rt.getMediaHandler() != null) {
                                rt.getMediaHandler().stopMedia();
                                SdpBuilder.SdpMediaInfo info = SdpBuilder.parseRemoteSdp(event.getSdp());
                                if (info != null) {
                                    rt.getMediaHandler().startMedia(info.remoteIp(), info.remoteRtpPort(),
                                            info.remoteRtcpPort(), rt.getLocalRtpPort(),
                                            info.payloadType(), info.codecType(), info.codecName());
                                }
                            }
                        }
                        case ENDED -> { ctx.setEndReason(event.getReason()); machine.transitionTo("COMPLETED"); }
                        case BYE -> {
                            if (rt.getBridge() != null) rt.getBridge().sendBye(ctx.getCallId());
                            ctx.setEndReason("User hangup");
                            machine.transitionTo("COMPLETED");
                        }
                        case MUTE -> {
                            if (rt.getMediaHandler() != null) rt.getMediaHandler().setMuted(true);
                        }
                        default -> {}
                    }
                })
                .timeout(MAX_CALL_DURATION_SEC, TimeUnit.SECONDS, "COMPLETED")
            .done()

            // ==================== COMPLETED (terminal) ====================
            .state("COMPLETED")
                .onEntry(machine -> {
                    CallContext ctx = machine.getPersistingEntity();
                    log.info("[Call-{}] COMPLETED: {}", machine.getId(), ctx.getEndReason());
                    machine.getContext().getBus().publish(new CallEvent.Ended(ctx.getCallId(), ctx.getEndReason()));
                })
                .finalState()
            .done()

            // ==================== FAILED (terminal) ====================
            .state("FAILED")
                .onEntry(machine -> {
                    CallContext ctx = machine.getPersistingEntity();
                    CallRuntimeContext rt = machine.getContext();
                    if (rt.getMediaHandler() != null) rt.getMediaHandler().stopMedia();
                    log.error("[Call-{}] FAILED: {}", machine.getId(), ctx.getEndReason());
                    rt.getBus().publish(new CallEvent.Failed(ctx.getCallId(), ctx.getEndReason()));
                })
                .finalState()
            .done()

            .build();
    }
}
