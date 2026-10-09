package com.example.ratelimit.traffic;

import java.time.Instant;

/**
 * One rate-limit decision as shown in the live traffic feed.
 *
 * <p>Deliberately holds no credential, header or body: {@code client} is a masked IP and {@code user} is a
 * salted hash prefix, so the feed can be read without revealing who a request came from.
 */
public record TrafficEvent(
        long id,
        Instant at,
        String method,
        String path,
        String outcome,
        int status,
        String policy,
        Long limit,
        Long remaining,
        Long retryAfterSeconds,
        String client,
        String user) {
}
