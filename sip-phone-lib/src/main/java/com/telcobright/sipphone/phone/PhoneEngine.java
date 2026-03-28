package com.telcobright.sipphone.phone;

import com.telcobright.sipphone.bus.EventBus;
import com.telcobright.sipphone.protocol.*;
import com.telcobright.sipphone.route.RouteStatus;
import com.telcobright.sipphone.route.health.*;
import com.telcobright.sipphone.route.health.impl.VertoRouteConnectionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Random;
import java.util.concurrent.*;

/**
 * Phone engine — wires all layers together.
 *
 * Creates: EventBus, RouteHealthRegistry, CallRouter, CallMachine, UiStateMachine.
 * Listens for UiAction.Register/Disconnect to manage route lifecycle.
 * Publishes RouteEvent when route status changes.
 *
 * This is the only class that knows about all layers. Each layer only
 * knows about the event bus and its own concerns.
 *
 * Usage:
 *   PhoneEngine engine = new PhoneEngine();
 *   engine.setMediaHandler(new LinuxMediaHandler());
 *   engine.start();
 *
 *   // UI subscribes to view model
 *   engine.getBus().subscribe(UiViewModel.class, vm -> updateUi(vm));
 *
 *   // UI fires actions
 *   engine.getUiStateMachine().handleAction(new UiAction.Dial("880...", "AMR-NB"));
 */
public class PhoneEngine {

    private static final Logger log = LoggerFactory.getLogger(PhoneEngine.class);
    private static final String ROUTE_ID = "primary";

    private final EventBus bus;
    private final UiStateMachine uiSm;
    private final CallMachine callMachine;
    private final CallRouter callRouter;
    private final RouteHealthRegistry routeRegistry;

    private final ScheduledExecutorService scheduler;
    private int localRtpPort;
    private String localIp;

    /* Active signaling bridge — for forwarding call events from route handler */
    private volatile VertoSignalingBridge activeVertoBridge;

    public PhoneEngine() {
        allocatePorts();
        localIp = detectLocalIp();

        bus = new EventBus("phone-bus");

        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "phone-sched"); t.setDaemon(true); return t;
        });

        /* Route health */
        routeRegistry = new RouteHealthRegistry(scheduler);
        routeRegistry.registerHandler("VERTO", new VertoRouteConnectionHandler());

        /* Call router */
        callRouter = new CallRouter(routeRegistry);
        callRouter.registerBridge(new VertoRouteSignalingBridge());

        /* Call machine */
        callMachine = new CallMachine(bus, callRouter, ROUTE_ID, localIp, localRtpPort);

        /* UI state machine */
        uiSm = new UiStateMachine(bus);
        uiSm.init();

        /* Listen for registration commands */
        bus.subscribe(UiAction.Register.class, this::onRegister);
        bus.subscribe(UiAction.Disconnect.class, this::onDisconnect);

        /* Route status → RouteEvent on bus */
        routeRegistry.setRouteStatusListener(this::onRouteStatusChanged);
    }

    public EventBus getBus() { return bus; }
    public UiStateMachine getUiStateMachine() { return uiSm; }
    public int getLocalRtpPort() { return localRtpPort; }
    public String getLocalIp() { return localIp; }

    public void setMediaHandler(PhoneController.MediaHandler handler) {
        callMachine.setMediaHandler(handler);
    }

    public void start() {
        log.info("PhoneEngine started (local={}:{})", localIp, localRtpPort);
    }

    public void shutdown() {
        if (routeRegistry.isRegistered(ROUTE_ID)) {
            routeRegistry.suspendRoute(ROUTE_ID);
        }
        bus.shutdown();
        scheduler.shutdownNow();
    }

    /* === Registration lifecycle === */

    private void onRegister(UiAction.Register reg) {
        if (routeRegistry.isRegistered(ROUTE_ID)) {
            routeRegistry.suspendRoute(ROUTE_ID);
            routeRegistry.unregisterRoute(ROUTE_ID);
        }

        RouteConfig config = RouteConfig.builder(ROUTE_ID, reg.serverUrl(), reg.protocol().toUpperCase())
                .routeName("Primary " + reg.protocol())
                .heartbeatMode(HeartbeatMode.PASSIVE)
                .passiveHeartbeatExpectedIntervalMs(60_000)
                .maxConsecutiveHeartbeatFailures(3)
                .connectTimeoutMs(15_000)
                .autoReconnect(true)
                .reconnectBaseDelayMs(3_000)
                .reconnectMaxDelayMs(30_000)
                .protocolParam("userId", reg.username())
                .protocolParam("password", reg.password())
                .build();

        routeRegistry.registerRoute(config);

        /* Set call listener on route context — forwards to bus as SignalingResult */
        RouteHealthContext ctx = routeRegistry.getRouteContext(ROUTE_ID);
        if (ctx != null) {
            ctx.setAttribute("callListener", new VertoRouteConnectionHandler.VertoCallListener() {
                @Override public void onIncomingCall(String callId, String callerNumber, String sdp) {
                    bus.publish(new SignalingResult.Incoming(callId, callerNumber, sdp));
                }
                @Override public void onCallAnswered(String callId, String sdp) {
                    bus.publish(new SignalingResult.Answered(callId, sdp != null ? sdp : ""));
                }
                @Override public void onCallEnded(String callId, String reason) {
                    bus.publish(new SignalingResult.Ended(callId, reason));
                }
                @Override public void onMediaUpdate(String callId, String sdp) {
                    bus.publish(new SignalingResult.Media(callId, sdp));
                }
                @Override public void onError(String error) {
                    log.error("Signaling error: {}", error);
                }
            });
        }

        routeRegistry.startRoute(ROUTE_ID);
        bus.publish(new RouteEvent.Connecting(ROUTE_ID));
    }

    private void onDisconnect(UiAction.Disconnect ignored) {
        if (routeRegistry.isRegistered(ROUTE_ID)) {
            routeRegistry.suspendRoute(ROUTE_ID);
        }
        bus.publish(new RouteEvent.Disconnected(ROUTE_ID, "User disconnect"));
    }

    private void onRouteStatusChanged(String routeId, RouteStatus status) {
        if (!ROUTE_ID.equals(routeId)) return;

        switch (status) {
            case UP -> bus.publish(new RouteEvent.Registered(routeId));
            case DOWN -> bus.publish(new RouteEvent.Disconnected(routeId, "Route down"));
            case SUSPENDED -> bus.publish(new RouteEvent.Disconnected(routeId, "Suspended"));
            default -> {}
        }
    }

    /* === Utility === */

    private void allocatePorts() {
        Random rng = new Random();
        for (int i = 0; i < 50; i++) {
            int port = 10000 + (rng.nextInt(10000) & 0xFFFE);
            try { new DatagramSocket(port).close(); new DatagramSocket(port+1).close(); localRtpPort = port; return; }
            catch (Exception ignored) {}
        }
        localRtpPort = 10000;
    }

    private String detectLocalIp() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName("8.8.8.8"), 10002);
            return s.getLocalAddress().getHostAddress();
        } catch (Exception e) { return "0.0.0.0"; }
    }
}
