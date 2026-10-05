package org.saket.eventbooking.common.cache;

import lombok.extern.slf4j.Slf4j;
import org.saket.eventbooking.common.dto.PageResponse;
import org.saket.eventbooking.event.dto.EventDetailResponse;
import org.saket.eventbooking.event.dto.EventSummaryResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Cache-aside on Redis through Spring's cache abstraction ({@code @Cacheable} / {@code @CacheEvict}).
 * <ul>
 *   <li>Each cache has its own typed JSON serializer, so no class names are stored in Redis and nothing
 *       polymorphic is deserialized.</li>
 *   <li>The manager is transaction-aware: puts and evictions inside a transaction run after it commits,
 *       so an eviction can't be undone by a reader that re-caches the pre-commit state.</li>
 *   <li>Writes are immediate (not Lettuce-async), so an eviction has happened by the time the admin
 *       request returns. {@code allEntries} evictions use SCAN, not KEYS, so they don't block Redis.</li>
 *   <li>Redis errors are logged and treated as a miss: the cache can never take the catalog down.</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableCaching
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig implements CachingConfigurer {

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper,
                                     CacheProperties properties) {
        var types = objectMapper.getTypeFactory();
        JavaType eventList = types.constructParametricType(PageResponse.class, EventSummaryResponse.class);
        JavaType categories = types.constructCollectionType(List.class, String.class);
        JavaType eventDetail = types.constructType(EventDetailResponse.class);

        // Immediate writes: with Lettuce, Spring Data Redis otherwise clears and puts asynchronously, and an
        // eviction that lands "soon" after the commit would let the next request read stale data.
        RedisCacheWriter writer = RedisCacheWriter.create(connectionFactory, cfg -> cfg
                .immediateWrites()
                .batchStrategy(BatchStrategies.scan(500)));
        return RedisCacheManager.builder(writer)
                .withInitialCacheConfigurations(Map.of(
                        CacheNames.EVENT_LIST, config(objectMapper, eventList, properties.eventListTtl()),
                        CacheNames.EVENT_CATEGORIES, config(objectMapper, categories, properties.eventListTtl()),
                        CacheNames.EVENT_DETAIL, config(objectMapper, eventDetail, properties.eventDetailTtl())))
                .disableCreateOnMissingCache() // a typo'd cache name fails fast instead of caching untyped
                .transactionAware()
                .build();
    }

    private static RedisCacheConfiguration config(ObjectMapper objectMapper, JavaType type, Duration ttl) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()
                .serializeValuesWith(SerializationPair.fromSerializer(new JacksonJsonRedisSerializer<>(objectMapper, type)));
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("Cache get failed on {} (serving from the database)", cache.getName(), e);
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("Cache put failed on {}", cache.getName(), e);
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.error("Cache evict failed on {}; entries may be stale until their TTL", cache.getName(), e);
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.error("Cache clear failed on {}; entries may be stale until their TTL", cache.getName(), e);
            }
        };
    }
}
