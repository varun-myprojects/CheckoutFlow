package com.checkout.engine.service;

import com.checkout.engine.dto.CreateProductRequest;
import com.checkout.engine.dto.ProductDetailResponse;
import com.checkout.engine.entity.Inventory;
import com.checkout.engine.entity.OutboxEvent;
import com.checkout.engine.entity.OutboxStatus;
import com.checkout.engine.entity.Product;
import com.checkout.engine.exception.DuplicateRequestException;
import com.checkout.engine.exception.ResourceNotFoundException;
import com.checkout.engine.repository.InventoryRepository;
import com.checkout.engine.repository.OutboxEventRepository;
import com.checkout.engine.repository.ProductRepository;
import com.checkout.engine.service.cache.CacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private CacheService cacheService;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @InjectMocks
    private ProductService productService;

    private Product sampleProduct;
    private Inventory sampleInventory;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(productService, "productTtlSeconds", 600L);

        sampleProduct = Product.builder()
                .id(1L)
                .sku("SKU-100")
                .name("MacBook Pro")
                .price(BigDecimal.valueOf(1999.99))
                .status("ACTIVE")
                .build();

        sampleInventory = Inventory.builder()
                .id(10L)
                .product(sampleProduct)
                .availableQty(25)
                .reservedQty(5)
                .version(1L)
                .build();
    }

    @Test
    void getProductById_cacheHit_returnsCachedValueWithoutQueryingDatabase() {
        ProductDetailResponse cachedResponse = ProductDetailResponse.builder()
                .id(1L)
                .sku("SKU-100")
                .name("MacBook Pro")
                .price(BigDecimal.valueOf(1999.99))
                .availableQty(25)
                .build();

        when(cacheService.get("product:1", ProductDetailResponse.class))
                .thenReturn(Optional.of(cachedResponse));

        ProductDetailResponse response = productService.getProductById(1L);

        assertThat(response).isSameAs(cachedResponse);
        verify(productRepository, never()).findById(any());
        verify(inventoryRepository, never()).findByProductId(any());
    }

    @Test
    void getProductById_cacheMiss_queriesDbAndStoresInCacheWithTtl() {
        when(cacheService.get("product:1", ProductDetailResponse.class))
                .thenReturn(Optional.empty());
        when(productRepository.findById(1L)).thenReturn(Optional.of(sampleProduct));
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(sampleInventory));

        ProductDetailResponse response = productService.getProductById(1L);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getSku()).isEqualTo("SKU-100");
        assertThat(response.getAvailableQty()).isEqualTo(25);
        assertThat(response.getReservedQty()).isEqualTo(5);

        verify(cacheService).put(eq("product:1"), any(ProductDetailResponse.class), eq(Duration.ofSeconds(600)));
    }

    @Test
    void getProductById_notFound_throwsResourceNotFoundException() {
        when(cacheService.get("product:999", ProductDetailResponse.class))
                .thenReturn(Optional.empty());
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getProductById(999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Product not found with id: '999'");
    }

    @Test
    void createProduct_success_savesProductAndEvictsProductCache() {
        CreateProductRequest request = CreateProductRequest.builder()
                .sku("SKU-NEW")
                .name("New Product")
                .description("New Description")
                .price(BigDecimal.valueOf(49.99))
                .initialStock(30)
                .build();

        Product createdProduct = Product.builder()
                .id(2L)
                .sku("SKU-NEW")
                .name("New Product")
                .price(BigDecimal.valueOf(49.99))
                .status("ACTIVE")
                .build();

        when(productRepository.existsBySku("SKU-NEW")).thenReturn(false);
        when(productRepository.save(any(Product.class))).thenReturn(createdProduct);
        when(inventoryRepository.save(any(Inventory.class))).thenReturn(sampleInventory);

        ProductDetailResponse response = productService.createProduct(request);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(2L);
        verify(cacheService).evict("product:2");
    }

    @Test
    void createProduct_duplicateSku_throwsDuplicateRequestException() {
        CreateProductRequest request = CreateProductRequest.builder()
                .sku("SKU-100")
                .name("MacBook Pro")
                .price(BigDecimal.valueOf(1999.99))
                .initialStock(10)
                .build();

        when(productRepository.existsBySku("SKU-100")).thenReturn(true);

        assertThatThrownBy(() -> productService.createProduct(request))
                .isInstanceOf(DuplicateRequestException.class)
                .hasMessageContaining("Product with SKU 'SKU-100' already exists");

        verify(productRepository, never()).save(any());
        verify(cacheService, never()).evict(any());
    }

    @Test
    void updateProductPrice_success_updatesPriceEvictsCacheAndWritesOutboxEvent() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(sampleProduct));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(sampleInventory));

        BigDecimal newPrice = BigDecimal.valueOf(1799.99);
        ProductDetailResponse response = productService.updateProductPrice(1L, newPrice);

        assertThat(response).isNotNull();
        assertThat(response.getPrice()).isEqualByComparingTo(newPrice);
        assertThat(sampleProduct.getPrice()).isEqualByComparingTo(newPrice);

        verify(cacheService).evict("product:1");

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());

        OutboxEvent capturedEvent = outboxCaptor.getValue();
        assertThat(capturedEvent.getAggregateType()).isEqualTo("PRODUCT");
        assertThat(capturedEvent.getAggregateId()).isEqualTo("1");
        assertThat(capturedEvent.getEventType()).isEqualTo("ProductPriceUpdated");
        assertThat(capturedEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(capturedEvent.getPayload()).contains("1799.99");
        assertThat(capturedEvent.getPayload()).contains("1999.99");
    }

    @Test
    void updateProductPrice_productNotFound_throwsResourceNotFoundException() {
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.updateProductPrice(999L, BigDecimal.valueOf(99.99)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Product not found with id: '999'");

        verify(cacheService, never()).evict(any());
        verify(outboxEventRepository, never()).save(any());
    }
}
