package com.example.ratelimit.traffic;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Live traffic for the admin console. Under {@code /api/admin/**}, so it needs ROLE_ADMIN like every other
 * admin endpoint (see SecurityConfig). Poll with {@code since} set to the largest event id already seen.
 */
@RestController
@RequestMapping("/api/admin/rate-limit/traffic")
public class TrafficController {

    private final TrafficRecorder recorder;
    private final TrafficStream stream;

    public TrafficController(TrafficRecorder recorder, TrafficStream stream) {
        this.recorder = recorder;
        this.stream = stream;
    }

    /**
     * Live push of decisions as Server-Sent Events. Events: {@code hello} (cursor), {@code traffic} (one
     * decision), {@code buckets} (the per-second chart, once a second). Without {@code since} it starts from
     * now; with it, it resumes after that event id.
     */
    @GetMapping(path = "/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.SseEmitter> stream(
            @RequestParam(required = false) Long since, @RequestParam(defaultValue = "60") int seconds) {
        if (!recorder.enabled()) {
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        var emitter = stream.subscribe(since, Math.min(Math.max(seconds, 10), 300));
        if (emitter == null) {
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS).build();
        }
        return org.springframework.http.ResponseEntity.ok()
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    public record TrafficResponse(boolean enabled, int capacity, long dropped, Instant serverTime,
            List<TrafficEvent> events, List<Map<String, Object>> buckets) {
    }

    @GetMapping
    public TrafficResponse traffic(@RequestParam(defaultValue = "0") long since,
            @RequestParam(defaultValue = "200") int limit, @RequestParam(defaultValue = "60") int seconds) {
        if (!recorder.enabled()) {
            return new TrafficResponse(false, recorder.capacity(), 0, Instant.now(), List.of(), List.of());
        }
        int boundedLimit = Math.min(Math.max(limit, 1), recorder.capacity());
        int boundedSeconds = Math.min(Math.max(seconds, 10), 300);
        return new TrafficResponse(true, recorder.capacity(), recorder.dropped(), Instant.now(),
                recorder.recent(since, boundedLimit), recorder.buckets(boundedSeconds));
    }
}
