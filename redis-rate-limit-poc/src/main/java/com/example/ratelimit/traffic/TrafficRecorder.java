package com.example.ratelimit.traffic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Records recent rate-limit decisions for the live traffic view.
 *
 * <p>This runs next to the hot path, so it is built to be unable to hurt it: {@link #record} only masks
 * the identifiers and offers the event to a bounded queue, and a single background thread does the Redis
 * writes. If the queue is full the event is dropped and counted; if Redis fails the event is lost and a
 * warning is logged at most every 30 seconds. A decision is never delayed, changed or failed by tracking.
 *
 * <p>Two things are kept in shared Redis so every instance contributes to one view: a capped list of
 * recent events (newest first), and per-second outcome counters for the chart.
 */
@Component
public class TrafficRecorder {

    private static final Logger log = LoggerFactory.getLogger(TrafficRecorder.class);
    static final String EVENTS = "ratelimit:traffic:events";
    static final String SEQ = "ratelimit:traffic:seq";
    static final String BUCKETS = "ratelimit:traffic:buckets";
    private static final long BUCKET_RETENTION_SECONDS = 300;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final boolean enabled;
    private final int capacity;
    private final String salt;
    private final ThreadPoolExecutor worker;
    private final AtomicLong dropped = new AtomicLong();
    private volatile long lastWarnMillis;
    private volatile long lastPruneSecond;

    public TrafficRecorder(StringRedisTemplate redis, ObjectMapper mapper, Clock clock,
            @Value("${ratelimit.traffic.enabled:true}") boolean enabled,
            @Value("${ratelimit.traffic.capacity:1000}") int capacity,
            @Value("${ratelimit.traffic.queue-size:10000}") int queueSize,
            @Value("${ratelimit.traffic.hash-salt:ratelimit-poc}") String salt) {
        this.redis = redis;
        this.mapper = mapper;
        this.clock = clock;
        this.enabled = enabled;
        this.capacity = Math.max(10, capacity);
        this.salt = salt;
        RejectedExecutionHandler dropAndCount = (task, executor) -> dropped.incrementAndGet();
        this.worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(100, queueSize)), runnable -> {
                    var thread = new Thread(runnable, "traffic-recorder");
                    thread.setDaemon(true);
                    return thread;
                }, dropAndCount);
    }

    public boolean enabled() {
        return enabled;
    }

    public int capacity() {
        return capacity;
    }

    public long dropped() {
        return dropped.get();
    }

    /** Never throws and never blocks: the caller is serving a request. */
    public void record(String method, String path, String outcome, int status, String policy, Long limit,
            Long remaining, Long retryAfterSeconds, String remoteAddr, String username) {
        if (!enabled) {
            return;
        }
        try {
            var at = Instant.now(clock);
            var client = maskIp(remoteAddr);
            var user = username == null ? null : hashUser(username);
            worker.execute(() -> write(at, method, path, outcome, status, policy, limit, remaining,
                    retryAfterSeconds, client, user));
        } catch (RuntimeException e) {
            dropped.incrementAndGet();
        }
    }

    private void write(Instant at, String method, String path, String outcome, int status, String policy,
            Long limit, Long remaining, Long retryAfterSeconds, String client, String user) {
        try {
            Long id = redis.opsForValue().increment(SEQ);
            var event = new TrafficEvent(id == null ? 0 : id, at, method, path, outcome, status, policy, limit,
                    remaining, retryAfterSeconds, client, user);
            redis.opsForList().leftPush(EVENTS, mapper.writeValueAsString(event));
            redis.opsForList().trim(EVENTS, 0, capacity - 1L);
            long second = at.getEpochSecond();
            redis.opsForHash().increment(BUCKETS, second + "|" + bucketName(outcome), 1);
            if (second - lastPruneSecond >= 10) {
                lastPruneSecond = second;
                prune(second);
            }
        } catch (Exception e) {
            long now = System.currentTimeMillis();
            if (now - lastWarnMillis > 30_000) {
                lastWarnMillis = now;
                log.warn("traffic recording skipped (decisions are unaffected): {}", e.getMessage());
            }
        }
    }

    private void prune(long nowSecond) {
        var stale = new ArrayList<Object>();
        for (Object field : redis.opsForHash().keys(BUCKETS)) {
            var text = String.valueOf(field);
            int bar = text.indexOf('|');
            if (bar > 0 && nowSecond - Long.parseLong(text.substring(0, bar)) > BUCKET_RETENTION_SECONDS) {
                stale.add(field);
            }
        }
        if (!stale.isEmpty()) {
            redis.opsForHash().delete(BUCKETS, stale.toArray());
        }
    }

    /** Events newer than {@code sinceId}, newest first, at most {@code limit}. */
    public List<TrafficEvent> recent(long sinceId, int limit) {
        var raw = redis.opsForList().range(EVENTS, 0, capacity - 1L);
        var out = new ArrayList<TrafficEvent>();
        if (raw == null) {
            return out;
        }
        for (var json : raw) {
            try {
                var event = mapper.readValue(json, TrafficEvent.class);
                if (event.id() > sinceId) {
                    out.add(event);
                }
            } catch (Exception e) {
                // A malformed entry is skipped; the rest of the feed is still useful.
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    /** One entry per second for the last {@code seconds}, oldest first, including seconds with no traffic. */
    public List<Map<String, Object>> buckets(int seconds) {
        long now = clock.instant().getEpochSecond();
        var entries = redis.opsForHash().entries(BUCKETS);
        var out = new ArrayList<Map<String, Object>>();
        for (long t = now - seconds + 1; t <= now; t++) {
            out.add(Map.of("t", t,
                    "allowed", count(entries, t, "allowed"),
                    "rejected", count(entries, t, "rejected"),
                    "error", count(entries, t, "error")));
        }
        return out;
    }

    private static long count(Map<Object, Object> entries, long second, String name) {
        var value = entries.get(second + "|" + name);
        return value == null ? 0 : Long.parseLong(value.toString());
    }

    private static String bucketName(String outcome) {
        return switch (outcome) {
            case "ALLOWED" -> "allowed";
            case "REJECTED" -> "rejected";
            default -> "error";
        };
    }

    /** 203.0.113.42 becomes 203.0.113.x; IPv6 keeps only its first two groups. */
    static String maskIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return "unknown";
        }
        var value = ip.startsWith("::ffff:") ? ip.substring(7) : ip;
        if (value.contains(":")) {
            var parts = value.split(":");
            return (parts.length > 1 ? parts[0] + ":" + parts[1] : parts[0]) + "::x";
        }
        int last = value.lastIndexOf('.');
        return last > 0 ? value.substring(0, last) + ".x" : "unknown";
    }

    private String hashUser(String username) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest((salt + ":" + username).getBytes(StandardCharsets.UTF_8));
            return "u-" + HexFormat.of().formatHex(digest, 0, 4);
        } catch (java.security.NoSuchAlgorithmException e) {
            return "u-unknown";
        }
    }
}
