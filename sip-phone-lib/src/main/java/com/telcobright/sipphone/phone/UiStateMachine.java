package com.telcobright.sipphone.phone;

import com.telcobright.sipphone.bus.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * UI state machine — decoupled from UI rendering.
 *
 * Receives: UiAction (from UI, async)
 *           CallEvent (from CallMachine, via bus)
 *           RouteEvent (from route health, via bus)
 * Publishes: UiViewModel (to UI, via bus)
 *
 * The UI subscribes to UiViewModel and re-renders.
 * This class owns all UI-related logic (what to show, when to enable buttons).
 * Platform-independent — works on Linux, Android, iOS.
 */
public class UiStateMachine {

    private static final Logger log = LoggerFactory.getLogger(UiStateMachine.class);

    private final EventBus bus;
    private volatile UiViewModel model = UiViewModel.idle();

    public UiStateMachine(EventBus bus) {
        this.bus = bus;

        /* Subscribe to events from other layers */
        bus.subscribe(CallEvent.class, this::onCallEvent);
        bus.subscribe(RouteEvent.class, this::onRouteEvent);
    }

    /**
     * Handle UI action (async, fire-and-forget from UI thread).
     */
    public void handleAction(UiAction action) {
        bus.publish(action); // dispatch async on bus thread
    }

    /**
     * Get current view model (for initial render).
     */
    public UiViewModel getModel() { return model; }

    /* Internal: subscribe to UiAction on bus for processing */
    {
        /* Deferred — subscribe after bus is set in constructor */
    }

    public void init() {
        bus.subscribe(UiAction.class, this::onUiAction);
    }

    /* === UiAction handling === */

    private void onUiAction(UiAction action) {
        switch (action) {
            case UiAction.Register reg -> {
                model = model.withReg(UiViewModel.RegState.CONNECTING, "Connecting...");
                publishModel();
                /* Actual registration handled by PhoneEngine which listens for UiAction.Register */
            }
            case UiAction.Dial dial -> {
                if (model.regState() != UiViewModel.RegState.REGISTERED) {
                    log.warn("Cannot dial — not registered");
                    return;
                }
                model = model.withCall(UiViewModel.CallUiState.DIALING, "", dial.destination(), dial.codec());
                publishModel();
                /* Dispatch to CallMachine */
                bus.publish(new CallRequest.Invite(dial.destination(), dial.codec()));
            }
            case UiAction.Hangup ignored -> {
                String cid = model.callId();
                model = model.withEnded("User hangup");
                publishModel();
                bus.publish(new CallRequest.Bye(cid));
            }
            case UiAction.Answer ans -> {
                model = model.withCall(UiViewModel.CallUiState.IN_CALL);
                publishModel();
                bus.publish(new CallRequest.Answer(ans.callId(), model.codec()));
            }
            case UiAction.Reject rej -> {
                model = model.withCall(UiViewModel.CallUiState.IDLE);
                publishModel();
                bus.publish(new CallRequest.Bye(rej.callId()));
            }
            case UiAction.Mute mute -> {
                model = model.withMute(mute.muted());
                publishModel();
                bus.publish(new CallRequest.MuteMedia(mute.muted()));
            }
            case UiAction.SendDtmf dtmf -> {
                bus.publish(new CallRequest.Dtmf(model.callId(), dtmf.digits()));
            }
            case UiAction.Disconnect ignored -> {
                model = UiViewModel.idle();
                publishModel();
                /* PhoneEngine handles actual disconnect */
            }
        }
    }

    /* === CallEvent handling === */

    private void onCallEvent(CallEvent event) {
        switch (event) {
            case CallEvent.Trying t ->
                model = model.withCall(UiViewModel.CallUiState.DIALING, t.callId(), t.destination(), model.codec());
            case CallEvent.Ringing r ->
                model = model.withCall(UiViewModel.CallUiState.RINGING);
            case CallEvent.Incoming inc ->
                model = model.withCall(UiViewModel.CallUiState.INCOMING, inc.callId(), inc.callerNumber(), "");
            case CallEvent.Answered ans ->
                model = model.withCall(UiViewModel.CallUiState.IN_CALL, ans.callId(), model.remoteNumber(), ans.codec());
            case CallEvent.MediaStarted ms ->
                model = new UiViewModel(model.regState(), model.regMessage(), model.callState(),
                        model.callId(), model.remoteNumber(), ms.codec(), "", model.muted(),
                        model.packetLoss(), model.jitter(), model.rtt());
            case CallEvent.Ended e ->
                model = model.withEnded(e.reason());
            case CallEvent.Failed f ->
                model = model.withEnded("Failed: " + f.reason());
        }
        publishModel();

        /* Auto-reset to IDLE after ended/failed */
        if (event instanceof CallEvent.Ended || event instanceof CallEvent.Failed) {
            model = new UiViewModel(model.regState(), model.regMessage(), UiViewModel.CallUiState.IDLE,
                    "", "", "", model.endReason(), false, 0, 0, 0);
            publishModel();
        }
    }

    /* === RouteEvent handling === */

    private void onRouteEvent(RouteEvent event) {
        switch (event) {
            case RouteEvent.Connecting c ->
                model = model.withReg(UiViewModel.RegState.CONNECTING, "Connecting...");
            case RouteEvent.Registered r ->
                model = model.withReg(UiViewModel.RegState.REGISTERED, "Registered");
            case RouteEvent.Disconnected d ->
                model = model.withReg(UiViewModel.RegState.DISCONNECTED, "Disconnected: " + d.reason());
            case RouteEvent.Reconnecting r ->
                model = model.withReg(UiViewModel.RegState.CONNECTING, "Reconnecting in " + r.delayMs() / 1000 + "s...");
        }
        publishModel();
    }

    private void publishModel() {
        bus.publish(model);
    }
}
