package com.checkout.engine.service.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private ObjectMapper objectMapper;
    private CacheService cacheService;

    static class TestDto {
        private String name;
        public TestDto() {}
        public TestDto(String name) { this.name = name; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        cacheService = new CacheService(redisTemplate, objectMapper);
        cacheService.setCacheEnabled(true);
    }

    @Test
    void get_whenCacheEnabledAndHit_returnsValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        TestDto dto = new TestDto("item1");
        when(valueOperations.get("key1")).thenReturn(dto);

        Optional<TestDto> result = cacheService.get("key1", TestDto.class);

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("item1");
    }

    @Test
    void get_whenCacheDisabled_returnsEmptyWithoutQueryingRedis() {
        cacheService.setCacheEnabled(false);

        Optional<TestDto> result = cacheService.get("key1", TestDto.class);

        assertThat(result).isEmpty();
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void getList_whenCacheEnabledAndHit_returnsList() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        List<Object> rawList = List.of(new TestDto("item1"), new TestDto("item2"));
        when(valueOperations.get("listKey")).thenReturn(rawList);

        Optional<List<TestDto>> result = cacheService.getList("listKey", TestDto.class);

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(2);
        assertThat(result.get().get(0).getName()).isEqualTo("item1");
    }

    @Test
    void put_whenCacheEnabled_setsValueWithTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        TestDto dto = new TestDto("cachedItem");

        cacheService.put("key1", dto, Duration.ofMinutes(5));

        verify(valueOperations).set("key1", dto, Duration.ofMinutes(5));
    }

    @Test
    void put_whenCacheDisabled_doesNotCallRedis() {
        cacheService.setCacheEnabled(false);

        cacheService.put("key1", new TestDto("test"), Duration.ofMinutes(5));

        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void evict_whenCacheEnabled_deletesKey() {
        when(redisTemplate.delete("key1")).thenReturn(true);

        cacheService.evict("key1");

        verify(redisTemplate).delete("key1");
    }

    @Test
    void evict_whenCacheDisabled_doesNotCallRedis() {
        cacheService.setCacheEnabled(false);

        cacheService.evict("key1");

        verify(redisTemplate, never()).delete("key1");
    }

    @Test
    void evictAll_deletesKeyCollection() {
        List<String> keys = List.of("key1", "key2");
        when(redisTemplate.delete(keys)).thenReturn(2L);

        cacheService.evictAll(keys);

        verify(redisTemplate).delete(keys);
    }

    @Test
    void redisException_handledGracefullyWithoutThrowing() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis connection timed out"));

        Optional<TestDto> result = cacheService.get("key1", TestDto.class);

        assertThat(result).isEmpty();
    }
}
