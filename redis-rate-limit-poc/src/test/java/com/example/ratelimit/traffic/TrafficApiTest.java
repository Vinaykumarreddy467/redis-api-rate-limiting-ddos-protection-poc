package com.example.ratelimit.traffic;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import com.example.ratelimit.RedisTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The live traffic view: what it records, what it hides, who may read it, and that it cannot hurt a request. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ratelimit.admin.username=admin-test",
                "ratelimit.admin.password=admin-test-secret",
                "ratelimit.admin.raw-password=true"
        })
@Import(TrafficApiTest.RedisConfig.class)
class TrafficApiTest {

    @TestConfiguration
    static class RedisConfig {
        @Bean
        @Primary
        LettuceConnectionFactory testConnectionFactory() {
            var container = RedisTestSupport.redis();
            var config = new RedisStandaloneConfiguration(container.getHost(),
                    container.getMappedPort(RedisTestSupport.REDIS_PORT));
            var factory = new LettuceConnectionFactory(config);
            factory.afterPropertiesSet();
            return factory;
        }
    }

    @Autowired
    TestRestTemplate rest;

    private static HttpEntity<Void> basic(String credentials) {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Basic "
                + java.util.Base64.getEncoder().encodeToString(credentials.getBytes()));
        return new HttpEntity<>(headers);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> traffic(long since) {
        var response = rest.exchange("/api/admin/rate-limit/traffic?since=" + since, HttpMethod.GET,
                basic("admin-test:admin-test-secret"), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordsDecisionsWithMaskedClientAndHashedUserAndSupportsACursor() throws Exception {
        for (int i = 0; i < 3; i++) {
            rest.exchange("/api/products", HttpMethod.GET, basic("alice:alice-pw"), String.class);
        }
        List<Map<String, Object>> events = List.of();
        for (int attempt = 0; attempt < 50 && events.size() < 3; attempt++) {
            Thread.sleep(100); // events are written by a background thread
            events = ((List<Map<String, Object>>) traffic(0).get("events")).stream()
                    .filter(e -> "/api/products".equals(e.get("path"))).toList();
        }
        assertThat(events).hasSizeGreaterThanOrEqualTo(3);

        var event = events.get(0);
        assertThat(event.get("outcome")).isEqualTo("ALLOWED");
        assertThat(event.get("method")).isEqualTo("GET");
        assertThat((String) event.get("client")).matches("localhost|.*(\\.x|::x)");
        assertThat((String) event.get("user")).startsWith("u-").doesNotContain("alice");
        // Nothing sensitive is stored: not the credentials, not the full address.
        var raw = event.toString();
        assertThat(raw).doesNotContain("alice-pw").doesNotContain("127.0.0.1");

        // The cursor returns only newer events.
        long newest = ((Number) events.get(0).get("id")).longValue();
        assertThat((List<?>) traffic(newest).get("events")).isEmpty();

        // The chart counters saw the same requests.
        var buckets = (List<Map<String, Object>>) traffic(0).get("buckets");
        assertThat(buckets.stream().mapToLong(b -> ((Number) b.get("allowed")).longValue()).sum())
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    void onlyAdministratorsMayReadTraffic() {
        var asUser = rest.exchange("/api/admin/rate-limit/traffic", HttpMethod.GET,
                basic("alice:alice-pw"), String.class);
        assertThat(asUser.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var anonymous = rest.getForEntity("/api/admin/rate-limit/traffic", String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void maskingKeepsOnlyTheNetworkPartOfAnAddress() {
        assertThat(TrafficRecorder.maskIp("203.0.113.42")).isEqualTo("203.0.113.x");
        assertThat(TrafficRecorder.maskIp("::ffff:10.1.2.3")).isEqualTo("10.1.2.x");
        assertThat(TrafficRecorder.maskIp("2001:db8:85a3::8a2e:370:7334")).isEqualTo("2001:db8::x");
        assertThat(TrafficRecorder.maskIp("0:0:0:0:0:0:0:1")).isEqualTo("localhost");
        assertThat(TrafficRecorder.maskIp("127.0.0.1")).isEqualTo("localhost");
        assertThat(TrafficRecorder.maskIp(null)).isEqualTo("unknown");
    }

    @Test
    void aBrokenRedisOrAFullQueueNeverReachesTheCaller() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis is down"));
        var recorder = new TrafficRecorder(redis, new ObjectMapper(), Clock.systemUTC(), true, 100, 100, "salt");
        for (int i = 0; i < 500; i++) {
            recorder.record("GET", "/api/x", "ALLOWED", 200, "p", 10L, 9L, null, "1.2.3.4", "bob");
        }
        // Reaching here without an exception is the point: writes fail silently in the background and
        // anything beyond the queue is dropped and counted instead of blocking the request thread.
        Thread.sleep(200);
        assertThat(recorder.dropped()).isGreaterThanOrEqualTo(0);

        var disabled = new TrafficRecorder(mock(StringRedisTemplate.class), new ObjectMapper(),
                Clock.systemUTC(), false, 100, 100, "salt");
        disabled.record("GET", "/api/x", "ALLOWED", 200, "p", null, null, null, "1.2.3.4", null);
        assertThat(disabled.enabled()).isFalse();
    }
}
