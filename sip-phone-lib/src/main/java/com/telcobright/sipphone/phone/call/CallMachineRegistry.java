package com.telcobright.sipphone.phone.call;

import com.telcobright.sipphone.bus.EventBus;
import com.telcobright.sipphone.phone.*;
import com.telcobright.sipphone.protocol.SignalingResult;
import com.telcobright.sipphone.statemachine.GenericStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Call machine registry — manages call leg state machines by callId.
 *
 * Subscribes to:
 *   - CallRequest (from UI state machine) → creates/finds machine
 *   - SignalingResult (from protocol bridge) → routes to machine by callId
 *
 * Publishes:
 *   - CallEvent (via machine entry actions) → consumed by UI state machine
 *
 * Generic pattern: one machine per call leg, lookup by callId.
 * Supports multi-leg / conference in future.
 */
public class CallMachineRegistry {

    private static final Logger log = LoggerFactory.getLogger(CallMachineRegistry.class);

    private final EventBus bus;
    private final CallMachineFactory factory;
    private final CallRouter callRouter;
    private final MediaHandler mediaHandler;
    private final String routeId;
    private final String localIp;
    private final int localRtpPort;

    /** Active call machines indexed by callId */
    private final Map<String, GenericStateMachine<CallContext, CallRuntimeContext>> machines = new ConcurrentHashMap<>();

    /** Machine ID → callId mapping (machine ID assigned at creation, callId assigned after invite) */
    private final Map<String, String> machineIdToCallId = new ConcurrentHashMap<>();

    public CallMachineRegistry(EventBus bus, CallRouter callRouter, MediaHandler mediaHandler,
                                String routeId, String localIp, int localRtpPort) {
        this.bus = bus;
        this.factory = new CallMachineFactory();
        this.callRouter = callRouter;
        this.mediaHandler = mediaHandler;
        this.routeId = routeId;
        this.localIp = localIp;
        this.localRtpPort = localRtpPort;

        /* Subscribe to events from protocol and UI */
        bus.subscribe(SignalingResult.class, this::onSignalingResult);
        bus.subscribe(CallRequest.class, this::onCallRequest);
    }

    /* === CallRequest handlers (from UI) === */

    private void onCallRequest(CallRequest req) {
        switch (req) {
            case CallRequest.Invite inv -> handleInvite(inv);
            case CallRequest.Bye bye -> handleBye(bye);
            case CallRequest.MuteMedia mute -> handleMute(mute);
            case CallRequest.Answer ans -> handleAnswer(ans);
            case CallRequest.Dtmf dtmf -> handleDtmf(dtmf);
        }
    }

    private void handleInvite(CallRequest.Invite inv) {
        String machineId = UUID.randomUUID().toString();

        CallContext ctx = new CallContext();
        ctx.setDestination(inv.destination());
        ctx.setCodec(inv.codec());

        CallRuntimeContext rt = new CallRuntimeContext(bus, callRouter, mediaHandler,
                routeId, localIp, localRtpPort);

        GenericStateMachine<CallContext, CallRuntimeContext> machine = factory.createMachine(machineId);
        machine.setPersistingEntity(ctx);
        machine.setContext(rt);

        /* When machine terminates → remove from registry */
        machine.setOnTerminated(id -> removeMachine(id));

        /* Track by machineId initially — callId comes after signaling invite */
        machineIdToCallId.put(machineId, machineId); // temp
        machines.put(machineId, machine);

        machine.start();
        machine.transitionTo("TRYING");

        /* After TRYING entry, ctx.callId is set by signaling bridge */
        String callId = ctx.getCallId();
        if (callId != null && !callId.isEmpty() && !callId.equals(machineId)) {
            machines.put(callId, machine);
            machineIdToCallId.put(machineId, callId);
            log.debug("Call machine {} mapped to callId {}", machineId, callId);
        }

        log.info("Created call machine: {} (callId={}, dest={})", machineId, callId, inv.destination());
    }

    private void handleBye(CallRequest.Bye bye) {
        String callId = bye.callId();
        GenericStateMachine<CallContext, CallRuntimeContext> machine = findMachine(callId);
        if (machine != null) {
            machine.fire(CallMachineEvent.bye(callId));
        }
    }

    private void handleAnswer(CallRequest.Answer ans) {
        GenericStateMachine<CallContext, CallRuntimeContext> machine = findMachine(ans.callId());
        if (machine != null) {
            machine.fire(CallMachineEvent.answered(ans.callId(), ""));
        }
    }

    private void handleMute(CallRequest.MuteMedia mute) {
        /* Mute applies to all active calls */
        for (var machine : machines.values()) {
            if (machine.isActive()) {
                machine.fire(new CallMachineEvent(CallMachineEvent.Type.MUTE));
            }
        }
    }

    private void handleDtmf(CallRequest.Dtmf dtmf) {
        GenericStateMachine<CallContext, CallRuntimeContext> machine = findMachine(dtmf.callId());
        if (machine != null) {
            CallRuntimeContext rt = machine.getContext();
            if (rt.getBridge() != null) {
                rt.getBridge().sendDtmf(dtmf.callId(), dtmf.digits());
            }
        }
    }

    /* === SignalingResult handlers (from protocol bridge) === */

    private void onSignalingResult(SignalingResult result) {
        String callId = extractCallId(result);

        if (result instanceof SignalingResult.Incoming inc) {
            handleIncomingCall(inc);
            return;
        }

        GenericStateMachine<CallContext, CallRuntimeContext> machine = findMachine(callId);
        if (machine == null) {
            log.debug("No machine for callId={}, ignoring {}", callId, result.getClass().getSimpleName());
            return;
        }

        /* Translate SignalingResult → CallMachineEvent and fire into machine */
        CallMachineEvent event = switch (result) {
            case SignalingResult.Media m -> CallMachineEvent.media(m.callId(), m.remoteSdp());
            case SignalingResult.Progress p -> CallMachineEvent.media(p.callId(), p.remoteSdp());
            case SignalingResult.Answered a -> CallMachineEvent.answered(a.callId(), a.remoteSdp());
            case SignalingResult.Ended e -> CallMachineEvent.ended(e.callId(), e.reason());
            case SignalingResult.Failed f -> CallMachineEvent.failed(f.callId(), f.reason(), f.causeCode());
            default -> null;
        };

        if (event != null) {
            machine.fire(event);
        }
    }

    private void handleIncomingCall(SignalingResult.Incoming inc) {
        String machineId = inc.callId(); // Use remote callId as machine ID

        CallContext ctx = new CallContext();
        ctx.setCallId(inc.callId());
        ctx.setCallerNumber(inc.callerNumber());
        ctx.setPendingSdp(inc.remoteSdp());

        CallRuntimeContext rt = new CallRuntimeContext(bus, callRouter, mediaHandler,
                routeId, localIp, localRtpPort);

        GenericStateMachine<CallContext, CallRuntimeContext> machine = factory.createMachine(machineId);
        machine.setPersistingEntity(ctx);
        machine.setContext(rt);
        machine.setOnTerminated(id -> removeMachine(id));

        machines.put(inc.callId(), machine);
        machine.start();

        bus.publish(new CallEvent.Incoming(inc.callId(), inc.callerNumber()));
        log.info("Incoming call machine: {} from {}", machineId, inc.callerNumber());
    }

    /* === Machine lookup === */

    private GenericStateMachine<CallContext, CallRuntimeContext> findMachine(String callId) {
        if (callId == null || callId.isEmpty()) return null;

        /* Direct lookup by callId */
        GenericStateMachine<CallContext, CallRuntimeContext> machine = machines.get(callId);
        if (machine != null) return machine;

        /* Search by callId in context */
        for (var m : machines.values()) {
            if (m.getPersistingEntity() != null && callId.equals(m.getPersistingEntity().getCallId())) {
                return m;
            }
        }
        return null;
    }

    private void removeMachine(String machineId) {
        machines.remove(machineId);
        String callId = machineIdToCallId.remove(machineId);
        if (callId != null) machines.remove(callId);
        log.debug("Removed call machine: {} (active={})", machineId, machines.size());
    }

    private String extractCallId(SignalingResult result) {
        return switch (result) {
            case SignalingResult.Trying t -> t.callId();
            case SignalingResult.Media m -> m.callId();
            case SignalingResult.Progress p -> p.callId();
            case SignalingResult.Answered a -> a.callId();
            case SignalingResult.Ended e -> e.callId();
            case SignalingResult.Failed f -> f.callId();
            case SignalingResult.Incoming i -> i.callId();
        };
    }

    /* === Query === */

    public int getActiveCallCount() { return machines.size(); }
    public Set<String> getActiveCallIds() { return Set.copyOf(machines.keySet()); }
    public boolean hasActiveCall() { return !machines.isEmpty(); }
}
