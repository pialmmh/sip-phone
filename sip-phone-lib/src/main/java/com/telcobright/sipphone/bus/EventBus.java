package com.telcobright.sipphone.bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Lightweight typed event bus — portable across Linux/Android/iOS.
 *
 * Features:
 * - Typed subscribe: subscribe(EventType.class, handler)
 * - Async dispatch: events delivered on a single-thread executor (ordered)
 * - Thread-safe: subscribe/unsubscribe/publish from any thread
 * - No external dependencies (no Guava, no Android Handler)
 *
 * Usage:
 *   bus.subscribe(CallEvent.Answered.class, e -> updateUi(e));
 *   bus.publish(new CallEvent.Answered(callId, sdp));
 */
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final Map<Class<?>, List<Consumer<?>>> subscribers = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    public EventBus() {
        this("event-bus");
    }

    public EventBus(String name) {
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Subscribe to events of a specific type.
     * Handler is called on the event bus thread (not the publisher's thread).
     */
    public <T> void subscribe(Class<T> eventType, Consumer<T> handler) {
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /**
     * Unsubscribe a handler.
     */
    public <T> void unsubscribe(Class<T> eventType, Consumer<T> handler) {
        List<Consumer<?>> handlers = subscribers.get(eventType);
        if (handlers != null) handlers.remove(handler);
    }

    /**
     * Publish an event asynchronously.
     * All matching subscribers are notified on the event bus thread.
     */
    @SuppressWarnings("unchecked")
    public void publish(Object event) {
        if (event == null) return;

        executor.execute(() -> {
            /* Deliver to exact type subscribers */
            deliver(event.getClass(), event);

            /* Deliver to superclass/interface subscribers (one level up) */
            Class<?> superClass = event.getClass().getSuperclass();
            if (superClass != null && superClass != Object.class) {
                deliver(superClass, event);
            }
            for (Class<?> iface : event.getClass().getInterfaces()) {
                deliver(iface, event);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void deliver(Class<?> type, Object event) {
        List<Consumer<?>> handlers = subscribers.get(type);
        if (handlers != null) {
            for (Consumer handler : handlers) {
                try {
                    handler.accept(event);
                } catch (Exception e) {
                    log.error("Event handler error for {}: {}", event.getClass().getSimpleName(), e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Shutdown the event bus executor.
     */
    public void shutdown() {
        executor.shutdownNow();
    }
}
