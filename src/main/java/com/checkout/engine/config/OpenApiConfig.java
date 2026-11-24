package com.checkout.engine.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI checkoutEngineOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Checkout Engine API")
                        .description("Production-grade E-Commerce Cart, Save-for-Later, and Checkout Backend " +
                                "featuring Redis Cache-Aside, Multi-Strategy Inventory Locking (Pessimistic, Redis Distributed, Optimistic), " +
                                "Deadlock Prevention, Transactional Outbox Pattern, and Apache Kafka Event Pipeline.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Checkout Engineering Team")
                                .email("engineering@checkout-flow.internal"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8080")
                                .description("Local Development Server")
                ));
    }
}
