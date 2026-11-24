package com.checkout.engine;

import com.checkout.engine.repository.InventoryRepository;
import com.checkout.engine.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest
class CheckoutEngineApplicationTests {

    @MockBean
    private ProductRepository productRepository;

    @MockBean
    private InventoryRepository inventoryRepository;

    @Test
    void contextLoads() {
    }
}
