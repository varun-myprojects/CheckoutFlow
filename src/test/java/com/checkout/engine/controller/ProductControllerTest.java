package com.checkout.engine.controller;

import com.checkout.engine.dto.CreateProductRequest;
import com.checkout.engine.dto.ProductDetailResponse;
import com.checkout.engine.dto.ProductResponse;
import com.checkout.engine.exception.DuplicateRequestException;
import com.checkout.engine.exception.GlobalExceptionHandler;
import com.checkout.engine.exception.ResourceNotFoundException;
import com.checkout.engine.service.ProductService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ProductService productService;

    @InjectMocks
    private ProductController productController;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(productController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    @Test
    void createProduct_success() throws Exception {
        CreateProductRequest request = CreateProductRequest.builder()
                .sku("SKU-100")
                .name("Test Product")
                .description("Test Description")
                .price(BigDecimal.valueOf(99.99))
                .initialStock(50)
                .build();

        ProductDetailResponse response = ProductDetailResponse.builder()
                .id(1L)
                .sku("SKU-100")
                .name("Test Product")
                .description("Test Description")
                .price(BigDecimal.valueOf(99.99))
                .status("ACTIVE")
                .availableQty(50)
                .reservedQty(0)
                .version(0L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(productService.createProduct(any(CreateProductRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.sku").value("SKU-100"))
                .andExpect(jsonPath("$.data.availableQty").value(50));
    }

    @Test
    void createProduct_duplicateSku_returnsConflict() throws Exception {
        CreateProductRequest request = CreateProductRequest.builder()
                .sku("SKU-EXISTS")
                .name("Test Product")
                .price(BigDecimal.valueOf(99.99))
                .initialStock(10)
                .build();

        when(productService.createProduct(any(CreateProductRequest.class)))
                .thenThrow(new DuplicateRequestException("Product with SKU 'SKU-EXISTS' already exists"));

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Product with SKU 'SKU-EXISTS' already exists"));
    }

    @Test
    void createProduct_validationError_returnsBadRequest() throws Exception {
        CreateProductRequest request = CreateProductRequest.builder()
                .sku("")
                .name("")
                .price(BigDecimal.valueOf(-5.0))
                .initialStock(-1)
                .build();

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void getProducts_returnsPaginatedList() throws Exception {
        ProductResponse p1 = ProductResponse.builder()
                .id(1L)
                .sku("SKU-1")
                .name("Prod 1")
                .price(BigDecimal.valueOf(10.0))
                .status("ACTIVE")
                .build();

        when(productService.getProducts(any()))
                .thenReturn(new PageImpl<>(List.of(p1), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].sku").value("SKU-1"));
    }

    @Test
    void getProductById_notFound_returns404() throws Exception {
        when(productService.getProductById(eq(999L)))
                .thenThrow(new ResourceNotFoundException("Product", "id", 999L));

        mockMvc.perform(get("/api/v1/products/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Product not found with id: '999'"));
    }
}
