package io.aegiscloud.controlplane.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The live feed of what the control loop is doing, as Server-Sent Events.
 *
 * <p>Polling was the wrong shape for this. The loop's whole claim is that it reacts
 * to a cluster on its own; a dashboard that learns about a scale-down thirty seconds
 * later, because that is when its next poll happened to land, cannot show that. Each
 * decision is pushed the moment it is made, from inside the same method that made it.
 *
 * <p>SSE rather than WebSocket because the traffic is entirely one-way: the browser
 * never sends anything back over this channel, and SSE reconnects by itself when a
 * laptop wakes up or a proxy drops an idle connection.
 */
@Component
public class ControlPlaneEvents {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneEvents.class);

    /** Long enough to outlive quiet periods; the browser reconnects when it expires. */
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(30);

    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

    /** Registers a new listener and hands back the emitter the controller returns. */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());

        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(e -> subscribers.remove(emitter));

        subscribers.add(emitter);

        // An immediate event so the client knows the stream is live rather than
        // merely open — an SSE connection with nothing on it is indistinguishable
        // from a hung one.
        send(emitter, "connected", Map.of(
                "at", Instant.now().toString(),
                "subscribers", subscribers.size()));

        return emitter;
    }

    /** Pushes one event to every live subscriber. */
    public void broadcast(String event, Object payload) {
        for (SseEmitter emitter : subscribers) {
            send(emitter, event, payload);
        }
    }

    /**
     * Keeps idle connections open.
     *
     * <p>Proxies and load balancers close connections that carry nothing for a minute
     * or two, and a control plane can legitimately have nothing to report for hours.
     * A comment frame costs nothing and is ignored by the EventSource API.
     */
    @Scheduled(fixedDelay = 25_000)
    public void heartbeat() {
        for (SseEmitter emitter : subscribers) {
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException e) {
                drop(emitter);
            }
        }
    }

    private void send(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(payload));
        } catch (IOException | IllegalStateException e) {
            // A client that went away is normal traffic, not an error worth raising:
            // the loop must never fail because a browser tab was closed mid-cycle.
            drop(emitter);
        }
    }

    private void drop(SseEmitter emitter) {
        subscribers.remove(emitter);
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // Already closed from the other end.
        }
    }

    /** How many dashboards are currently watching; surfaced on the health endpoint. */
    public int subscriberCount() {
        return subscribers.size();
    }
}
