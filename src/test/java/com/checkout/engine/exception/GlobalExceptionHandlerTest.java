package com.checkout.engine.exception;

import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @RestController
    @RequestMapping("/test")
    static class TestController {

        @PostMapping("/idempotency")
        public String testIdempotency(
                @RequestHeader(value = "Idempotency-Key", required = true) String key
        ) {
            if (key.isBlank()) {
                throw new IllegalArgumentException("Idempotency-Key header must not be blank");
            }
            return "OK";
        }

        @PostMapping("/body")
        public String testBody(@RequestBody TestPayload payload) {
            return "Payload received: " + payload.name;
        }

        @GetMapping("/param/{id}")
        public String testParam(@PathVariable Long id) {
            return "Id: " + id;
        }

        @GetMapping("/db-conflict")
        public String testDbConflict() {
            throw new DataIntegrityViolationException("Duplicate entry 'chk-123' for key 'orders.uk_orders_idempotency_key'");
        }

        @GetMapping("/lock-timeout")
        public String testLockTimeout() {
            throw new LockAcquisitionException("Could not acquire inventory lock for product: 5");
        }

        @GetMapping("/optimistic-spring")
        public String testOptimisticSpring() {
            throw new ObjectOptimisticLockingFailureException("Inventory", 5L);
        }

        @GetMapping("/optimistic-jpa")
        public String testOptimisticJpa() {
            throw new OptimisticLockException("Row was updated or deleted by another transaction");
        }

        @GetMapping("/secret-error")
        public String testSecretError() {
            throw new RuntimeException("secret db error");
        }
    }

    static class TestPayload {
        public String name;
        public Integer count;
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void missingIdempotencyKeyHeader_returns400() throws Exception {
        mockMvc.perform(post("/test/idempotency"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message", containsString("Required request header 'Idempotency-Key' is missing")));
    }

    @Test
    void blankIdempotencyKeyHeader_returns400() throws Exception {
        mockMvc.perform(post("/test/idempotency")
                        .header("Idempotency-Key", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Idempotency-Key header must not be blank"));
    }

    @Test
    void malformedJsonBody_returns400() throws Exception {
        mockMvc.perform(post("/test/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\": \"invalid_int_without_quote}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Malformed JSON request body"));
    }

    @Test
    void invalidPathVariableType_returns400() throws Exception {
        mockMvc.perform(get("/test/param/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message", containsString("Invalid value 'not-a-number' for parameter 'id'")));
    }

    @Test
    void dataIntegrityViolationException_returns409_withoutLeakingSqlDetails() throws Exception {
        mockMvc.perform(get("/test/db-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message", not(containsString("uk_orders_idempotency_key"))))
                .andExpect(jsonPath("$.message", not(containsString("Duplicate entry"))));
    }

    @Test
    void unexpectedRuntimeException_returns500_withoutLeakingSecretMessage() throws Exception {
        mockMvc.perform(get("/test/secret-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An unexpected internal error occurred. Please try again later."))
                .andExpect(jsonPath("$.message", not(containsString("secret db error"))));
    }

    @Test
    void lockAcquisitionException_returns503_serviceUnavailable() throws Exception {
        mockMvc.perform(get("/test/lock-timeout"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.message", containsString("Could not acquire inventory lock for product: 5")));
    }

    @Test
    void objectOptimisticLockingFailureException_returns409_conflict() throws Exception {
        mockMvc.perform(get("/test/optimistic-spring"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message", containsString("concurrent update conflict")));
    }

    @Test
    void optimisticLockException_returns409_conflict() throws Exception {
        mockMvc.perform(get("/test/optimistic-jpa"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message", containsString("concurrent update conflict")));
    }
}
