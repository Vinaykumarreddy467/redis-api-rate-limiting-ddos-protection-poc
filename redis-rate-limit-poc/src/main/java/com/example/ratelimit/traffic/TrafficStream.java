package com.example.ratelimit.traffic;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pushes recorded decisions to open admin consoles as Server-Sent Events.
 *
 * <p>A decision recorded on this instance wakes the pusher immediately, so it reaches the browser within
 * milliseconds. A short timer also tails the shared Redis list, which is how decisions recorded by other
 * instances arrive. Each subscriber has its own cursor (the largest event id it has been sent), and an
 * event is only ever sent after it has been stored, so what the page shows is what the backend holds.
 *
 * <p>This never sits on the request path: it reads from Redis on its own thread, a slow or closed browser
 * only affects its own subscription, and the number of subscribers is capped.
 */
@Component
public class TrafficStream {

    private static final Logger log = LoggerFactory.getLogger(TrafficStream.class);
    static final int MAX_SUBSCRIBERS = 5;
    private static final long TAIL_MS = 400;
    private static final long TIMEOUT_MS = 30 * 60_000L;
    private static final int BATCH = 500;

    private final TrafficRecorder recorder;
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService pusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "traffic-stream");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean pushQueued = new AtomicBoolean();
    private volatile long lastBucketsMillis;
    private volatile long lastBeatMillis;

    private static final class Subscriber {
        final SseEmitter emitter;
        final int chartSeconds;
        long cursor;

        Subscriber(SseEmitter emitter, long cursor, int chartSeconds) {
            this.emitter = emitter;
            this.cursor = cursor;
            this.chartSeconds = chartSeconds;
        }
    }

    public TrafficStream(TrafficRecorder recorder) {
        this.recorder = recorder;
        recorder.addListener(this::pushSoon);
        pusher.scheduleWithFixedDelay(this::pushSafely, TAIL_MS, TAIL_MS, TimeUnit.MILLISECONDS);
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    /**
     * Opens a stream. {@code since == null} starts from now (no history); a value resumes after that event id,
     * which is how a reconnecting page avoids a gap.
     *
     * @return the emitter, or {@code null} when the subscriber cap is reached
     */
    public SseEmitter subscribe(Long since, int chartSeconds) {
        if (subscribers.size() >= MAX_SUBSCRIBERS) {
            return null;
        }
        var emitter = new SseEmitter(TIMEOUT_MS);
        long cursor = since != null ? since : recorder.latestId();
        var subscriber = new Subscriber(emitter, cursor, chartSeconds);
        subscribers.add(subscriber);
        Runnable drop = () -> subscribers.remove(subscriber);
        emitter.onCompletion(drop);
        emitter.onTimeout(() -> {
            drop.run();
            emitter.complete();
        });
        emitter.onError(error -> drop.run());
        try {
            emitter.send(SseEmitter.event().name("hello").data(
                    new Hello(cursor, recorder.capacity(), recorder.dropped())));
        } catch (IOException | IllegalStateException e) {
            drop.run();
        }
        pushSoon();
        return emitter;
    }

    public record Hello(long cursor, int capacity, long dropped) {
    }

    /** Coalesces bursts: many events recorded together trigger one push pass. */
    private void pushSoon() {
        if (subscribers.isEmpty() || !pushQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            pusher.execute(this::pushSafely);
        } catch (RuntimeException e) {
            pushQueued.set(false);
        }
    }

    private void pushSafely() {
        pushQueued.set(false);
        try {
            push();
        } catch (RuntimeException e) {
            log.debug("traffic stream pass skipped: {}", e.getMessage());
        }
    }

    private synchronized void push() {
        if (subscribers.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean sendBuckets = now - lastBucketsMillis >= 1000;
        boolean sendBeat = now - lastBeatMillis >= 15_000;
        for (var subscriber : subscribers) {
            try {
                var fresh = new ArrayList<>(recorder.recent(subscriber.cursor, BATCH));
                Collections.reverse(fresh); // stored newest first; the page wants arrival order
                for (var event : fresh) {
                    subscriber.emitter.send(SseEmitter.event().name("traffic").id(String.valueOf(event.id()))
                            .data(event));
                    subscriber.cursor = Math.max(subscriber.cursor, event.id());
                }
                if (sendBuckets) {
                    subscriber.emitter.send(SseEmitter.event().name("buckets")
                            .data(new Buckets(recorder.buckets(subscriber.chartSeconds), recorder.dropped())));
                } else if (sendBeat) {
                    subscriber.emitter.send(SseEmitter.event().comment("keep-alive"));
                }
            } catch (IOException | IllegalStateException e) {
                subscribers.remove(subscriber);
            } catch (RuntimeException e) {
                log.debug("traffic stream subscriber skipped: {}", e.getMessage());
            }
        }
        if (sendBuckets) {
            lastBucketsMillis = now;
        }
        if (sendBeat) {
            lastBeatMillis = now;
        }
    }

    public record Buckets(List<java.util.Map<String, Object>> buckets, long dropped) {
    }

    @PreDestroy
    void shutdown() {
        pusher.shutdownNow();
        for (var subscriber : subscribers) {
            try {
                subscriber.emitter.complete();
            } catch (RuntimeException ignored) {
                // closing anyway
            }
        }
    }
}
