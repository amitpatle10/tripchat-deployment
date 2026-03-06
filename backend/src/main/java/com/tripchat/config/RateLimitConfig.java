package com.tripchat.config;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

/**
 * RateLimitConfig — wires Bucket4j's distributed proxy manager to Redis.
 *
 * Pattern: Proxy (Bucket4j ProxyManager acts as a proxy to Redis-backed bucket state).
 * Each call to proxyManager.builder().build(key, config) returns a Bucket whose
 * state (token count + timestamps) lives in Redis, shared across all ECS instances.
 *
 * Key type: String  ("rl:login:1.2.3.4")
 * Value type: byte[] (Bucket4j serializes bucket state as a compact byte array)
 *
 * withExpirationAfterWrite(1h): Redis TTL for inactive bucket keys.
 * An IP that stops making requests has its key expire after 1 hour — prevents unbounded
 * Redis memory growth. On next request, a fresh full bucket is issued automatically.
 *
 * Why standalone RedisClient (not Spring's RedisTemplate)?
 * LettuceBasedProxyManager needs a StatefulRedisConnection with a byte-array value codec.
 * Spring's RedisTemplate uses String or Object codecs; we extract the underlying Lettuce
 * client from LettuceConnectionFactory and create a separate connection with the exact
 * codec Bucket4j requires. The connection is lightweight (shares Lettuce's event loop).
 */
@Configuration
@RequiredArgsConstructor
public class RateLimitConfig {

    private final LettuceConnectionFactory lettuceConnectionFactory;

    @Bean
    public LettuceBasedProxyManager<String> rateLimitProxyManager() {
        // Extract the underlying Lettuce RedisClient from Spring's connection factory.
        // Safe cast: we configure a single Redis node (not Cluster/Sentinel), so
        // getNativeClient() always returns RedisClient (not RedisClusterClient).
        RedisClient redisClient = (RedisClient) lettuceConnectionFactory.getNativeClient();

        // Mixed codec: String keys (human-readable), byte[] values (Bucket4j state).
        StatefulRedisConnection<String, byte[]> connection =
                redisClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));

        return LettuceBasedProxyManager.builderFor(connection)
                .withExpirationStrategy(ExpirationAfterWriteStrategy.fixedTimeToLive(Duration.ofHours(1)))
                .build();
    }
}
