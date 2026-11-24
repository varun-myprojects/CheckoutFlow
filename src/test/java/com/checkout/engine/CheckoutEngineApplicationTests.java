package com.checkout.engine;

import com.checkout.engine.repository.CartItemRepository;
import com.checkout.engine.repository.CartRepository;
import com.checkout.engine.repository.InventoryRepository;
import com.checkout.engine.repository.InventoryReservationRepository;
import com.checkout.engine.repository.OrderItemRepository;
import com.checkout.engine.repository.OrderRepository;
import com.checkout.engine.repository.PaymentTransactionRepository;
import com.checkout.engine.repository.ProcessedWebhookEventRepository;
import com.checkout.engine.repository.ProductRepository;
import com.checkout.engine.repository.SavedItemRepository;
import com.checkout.engine.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest
class CheckoutEngineApplicationTests {

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private ProductRepository productRepository;

    @MockBean
    private InventoryRepository inventoryRepository;

    @MockBean
    private CartRepository cartRepository;

    @MockBean
    private CartItemRepository cartItemRepository;

    @MockBean
    private SavedItemRepository savedItemRepository;

    @MockBean
    private OrderRepository orderRepository;

    @MockBean
    private OrderItemRepository orderItemRepository;

    @MockBean
    private InventoryReservationRepository inventoryReservationRepository;

    @MockBean
    private PaymentTransactionRepository paymentTransactionRepository;

    @MockBean
    private ProcessedWebhookEventRepository processedWebhookEventRepository;

    @MockBean
    private org.springframework.data.redis.connection.RedisConnectionFactory redisConnectionFactory;

    @MockBean
    private org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;

    @Test
    void contextLoads() {
    }
}
