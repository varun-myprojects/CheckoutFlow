package com.checkout.engine.service.cache;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class CacheService {

    @Value("${app.cache.enabled:true}")
    private boolean enabled;

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public CacheService(
            @Autowired(required = false) RedisTemplate<String, Object> redisTemplate,
            ObjectMapper objectMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean isCacheEnabled() {
        return enabled;
    }

    public void setCacheEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public <T> Optional<T> get(String key, Class<T> clazz) {
        if (!enabled || redisTemplate == null) {
            return Optional.empty();
        }
        try {
            Object raw = redisTemplate.opsForValue().get(key);
            if (raw == null) {
                return Optional.empty();
            }
            if (clazz.isInstance(raw)) {
                return Optional.of(clazz.cast(raw));
            }
            return Optional.ofNullable(objectMapper.convertValue(raw, clazz));
        } catch (Exception e) {
            log.warn("Cache get failed for key '{}': {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public <T> Optional<List<T>> getList(String key, Class<T> elementClass) {
        if (!enabled || redisTemplate == null) {
            return Optional.empty();
        }
        try {
            Object raw = redisTemplate.opsForValue().get(key);
            if (raw == null) {
                return Optional.empty();
            }
            JavaType type = objectMapper.getTypeFactory().constructCollectionType(List.class, elementClass);
            List<T> list = objectMapper.convertValue(raw, type);
            return Optional.ofNullable(list);
        } catch (Exception e) {
            log.warn("Cache getList failed for key '{}': {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public void put(String key, Object value, Duration ttl) {
        if (!enabled || redisTemplate == null || value == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
            log.debug("Cached key '{}' with TTL {}", key, ttl);
        } catch (Exception e) {
            log.warn("Cache put failed for key '{}': {}", key, e.getMessage());
        }
    }

    public void evict(String key) {
        if (!enabled || redisTemplate == null || key == null) {
            return;
        }
        try {
            Boolean deleted = redisTemplate.delete(key);
            log.debug("Evicted cache key '{}', deleted={}", key, deleted);
        } catch (Exception e) {
            log.warn("Cache evict failed for key '{}': {}", key, e.getMessage());
        }
    }

    public void evictAll(Collection<String> keys) {
        if (!enabled || redisTemplate == null || keys == null || keys.isEmpty()) {
            return;
        }
        try {
            Long count = redisTemplate.delete(keys);
            log.debug("Evicted {} cache keys from collection", count);
        } catch (Exception e) {
            log.warn("Cache evictAll failed for keys {}: {}", keys, e.getMessage());
        }
    }
}
