package com.checkout.engine.config;

import com.checkout.engine.service.lock.InventoryLockStrategy;
import com.checkout.engine.service.lock.OptimisticLockStrategy;
import com.checkout.engine.service.lock.PessimisticDbLockStrategy;
import com.checkout.engine.service.lock.RedisDistributedLockStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Slf4j
@Configuration
public class InventoryLockConfig {

    @Bean
    @Primary
    public InventoryLockStrategy primaryInventoryLockStrategy(
            @Value("${checkout.lock.strategy:pessimistic}") String strategy,
            PessimisticDbLockStrategy pessimistic,
            ObjectProvider<RedisDistributedLockStrategy> redisProvider,
            OptimisticLockStrategy optimistic) {

        log.info("Configuring InventoryLockStrategy with strategy: {}", strategy);

        return switch (strategy.trim().toLowerCase()) {
            case "redis" -> {
                RedisDistributedLockStrategy redis = redisProvider.getIfAvailable();
                if (redis == null) {
                    throw new IllegalStateException("RedisDistributedLockStrategy requested but bean is unavailable");
                }
                yield redis;
            }
            case "optimistic" -> optimistic;
            case "pessimistic" -> pessimistic;
            default -> throw new IllegalArgumentException("Unknown inventory lock strategy: " + strategy);
        };
    }
}
