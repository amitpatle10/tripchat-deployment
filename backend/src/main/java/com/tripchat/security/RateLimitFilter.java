package com.tripchat.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * RateLimitFilter — Token Bucket rate limiting via Bucket4j + Redis.
 *
 * Pattern: Intercepting Filter.
 * Runs BEFORE Spring Security (Order.HIGHEST_PRECEDENCE < Spring Security's -100).
 * This means even unauthenticated brute-force attempts to any endpoint are rate-limited
 * at the outermost layer, before JWT validation or controller logic runs.
 *
 * Why filter (not HandlerInterceptor)?
 * HandlerInterceptors run inside Spring MVC, after Spring Security resolves the request.
 * A servlet filter runs at the container level — earlier, cheaper, and can reject requests
 * before any Spring processing happens.
 *
 * Algorithm: Token Bucket (Bucket4j)
 *   - intervally refill (auth endpoints): all tokens restored at once after window expires.
 *     After 5 bad logins, user waits the full minute — strict, standard for auth.
 *   - greedy refill (general API): tokens drip back smoothly over the window.
 *     A burst of 5 requests at app startup is fine; only sustained high rates are blocked.
 *
 * Keys: "rl:{endpoint}:{clientIp}" — per-IP, per-endpoint bucket in Redis.
 *
 * Fail-open: if Redis is unavailable, the request is allowed through (logged as WARN).
 * Tradeoff: rate limiting is disabled during a Redis outage, but the chat service stays up.
 * At 1000 DAUs a momentary Redis outage is a smaller risk than total service unavailability.
 *
 * Response on limit exceeded: 429 Too Many Requests with Retry-After header.
 * Header X-Rate-Limit-Remaining is set on allowed requests.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH    = "/api/v1/auth/login";
    private static final String REGISTER_PATH = "/api/v1/auth/register";

    private final LettuceBasedProxyManager<String> rateLimitProxyManager;
    private final ObjectMapper objectMapper;

    @Value("${rate-limit.login.capacity:5}")
    private int loginCapacity;

    @Value("${rate-limit.login.refill-minutes:1}")
    private int loginRefillMinutes;

    @Value("${rate-limit.register.capacity:3}")
    private int registerCapacity;

    @Value("${rate-limit.register.refill-minutes:10}")
    private int registerRefillMinutes;

    @Value("${rate-limit.general.capacity:60}")
    private int generalCapacity;

    @Value("${rate-limit.general.refill-minutes:1}")
    private int generalRefillMinutes;

    // Initialized in @PostConstruct after @Value fields are injected.
    private BucketConfiguration loginConfig;
    private BucketConfiguration registerConfig;
    private BucketConfiguration generalConfig;

    @PostConstruct
    void buildConfigurations() {
        // intervally: all tokens refill at once after the window elapses.
        // After exhausting 5 login attempts, the user waits the full minute.
        loginConfig = BucketConfiguration.builder()
                .addLimit(Bandwidth.classic(loginCapacity,
                        Refill.intervally(loginCapacity, Duration.ofMinutes(loginRefillMinutes))))
                .build();

        registerConfig = BucketConfiguration.builder()
                .addLimit(Bandwidth.classic(registerCapacity,
                        Refill.intervally(registerCapacity, Duration.ofMinutes(registerRefillMinutes))))
                .build();

        // greedy: tokens drip back at a constant rate (1 token every refill-period/capacity seconds).
        // 60 tokens / 60s = 1 token refills every second — smooth, allows normal usage bursts.
        generalConfig = BucketConfiguration.builder()
                .addLimit(Bandwidth.classic(generalCapacity,
                        Refill.greedy(generalCapacity, Duration.ofMinutes(generalRefillMinutes))))
                .build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String ip   = extractClientIp(request);
        String path = request.getRequestURI();

        String bucketKey;
        BucketConfiguration config;

        if (LOGIN_PATH.equals(path)) {
            bucketKey = "rl:login:" + ip;
            config    = loginConfig;
        } else if (REGISTER_PATH.equals(path)) {
            bucketKey = "rl:register:" + ip;
            config    = registerConfig;
        } else {
            bucketKey = "rl:api:" + ip;
            config    = generalConfig;
        }

        try {
            Bucket bucket = rateLimitProxyManager.builder().build(bucketKey, config);
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

            if (probe.isConsumed()) {
                response.setHeader("X-Rate-Limit-Remaining", String.valueOf(probe.getRemainingTokens()));
                filterChain.doFilter(request, response);
            } else {
                long retryAfterSeconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill());
                sendRateLimitResponse(response, retryAfterSeconds);
            }
        } catch (Exception e) {
            // Fail-open: Redis unavailable → allow the request through.
            // Tradeoff: rate limiting disabled during Redis outage, but service stays available.
            log.warn("Rate limit check failed for ip={} path={} — allowing request. reason={}",
                    ip, path, e.getMessage());
            filterChain.doFilter(request, response);
        }
    }

    private void sendRateLimitResponse(HttpServletResponse response, long retryAfterSeconds)
            throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", 429);
        body.put("error", "Too Many Requests");
        body.put("message", "Rate limit exceeded. Try again in " + retryAfterSeconds + " seconds.");

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    /**
     * Extracts the real client IP from the request.
     *
     * X-Forwarded-For format: "client, proxy1, proxy2"
     * The first (leftmost) IP is always the real client — set by CloudFront or the
     * first trusted proxy. We take [0] after splitting on comma.
     *
     * Note: This header can be spoofed if the request bypasses CloudFront/ALB and
     * hits the app directly. In production, ALB should be configured to strip
     * untrusted X-Forwarded-For headers (or use X-Forwarded-For override policy).
     */
    private String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
