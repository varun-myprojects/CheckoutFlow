package com.checkout.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

//@SpringBootApplication(exclude = {
//        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
//        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
//        org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration.class,
//        org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration.class,
//        org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration.class
//})
// Or remove exclude but run "docker compose up -d" to create connections for dependencies on MySQL(localhost:3306), JPA, and Flyway in pom.xml
@SpringBootApplication
public class CheckoutEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(CheckoutEngineApplication.class, args);
    }
}
