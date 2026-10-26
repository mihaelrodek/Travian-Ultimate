package com.travianbot.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Fan-out hub for Server-Sent Events (roadmap NOT4). Every open browser tab
 * holds one {@link SseEmitter}; {@link #broadcast} pushes a named JSON event to
 * all of them, so the UI updates the moment something happens instead of
 * waiting for its next poll.
 * <p>
 * Single-user, local tool → no per-connection filtering: every tab sees every
 * event and each consumer decides what's relevant (e.g. the log page ignores
 * events for other accounts). The REST polls stay in place as a fallback, so a
 * dropped stream degrades to "slightly less live" rather than "broken".
 * <p>
 * Threading: emitters live in a {@link CopyOnWriteArraySet}; sends to a single
 * emitter are serialized on the emitter's monitor because Spring's SseEmitter
 * is not safe for concurrent writes (events can originate from scraper threads,
 * {@code @Async} notifiers, and the keepalive scheduler at once). A send
 * failure means the client vanished — we drop that emitter and move on.
 */
@Component
public class SseEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(SseEventBroadcaster.class);

    /** Long-lived stream; the browser's EventSource reconnects if it ever completes. */
    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L; // 30 min

    private final Set<SseEmitter> emitters = new CopyOnWriteArraySet<>();

    /**
     * Register a new subscriber. Wires up cleanup on completion/timeout/error and
     * sends a hello comment so the connection is flushed and established immediately.
     */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError(e -> emitters.remove(emitter));
        emitters.add(emitter);
        trySend(emitter, SseEmitter.event().comment("connected"));
        log.debug("SSE subscriber added — {} open", emitters.size());
        return emitter;
    }

    /**
     * Push a named event carrying {@code payload} (serialized as JSON) to every
     * open stream. No-op when nobody is listening.
     */
    public void broadcast(String eventName, Object payload) {
        if (emitters.isEmpty()) return;
        SseEmitter.SseEventBuilder event = SseEmitter.event()
                .name(eventName)
                .data(payload, MediaType.APPLICATION_JSON);
        for (SseEmitter emitter : emitters) {
            trySend(emitter, event);
        }
    }

    /**
     * Broadcast only after the current transaction commits — so the UI never
     * receives an event for a row that then rolls back. Falls back to an
     * immediate broadcast when there's no active transaction.
     */
    public void broadcastAfterCommit(String eventName, Object payload) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    broadcast(eventName, payload);
                }
            });
        } else {
            broadcast(eventName, payload);
        }
    }

    /**
     * Comment ping every 20s so intermediaries (Vite dev proxy, reverse proxies)
     * don't reap an idle connection. Comments are ignored by EventSource, so this
     * never reaches an event handler.
     */
    @Scheduled(fixedRate = 20_000L)
    public void keepAlive() {
        if (emitters.isEmpty()) return;
        for (SseEmitter emitter : emitters) {
            trySend(emitter, SseEmitter.event().comment("ping"));
        }
    }

    private void trySend(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            synchronized (emitter) {
                emitter.send(event);
            }
        } catch (IOException | IllegalStateException e) {
            // Client went away (tab closed, network dropped) or the emitter already
            // completed — forget it. The browser will reconnect with a fresh one.
            emitters.remove(emitter);
        }
    }
}
