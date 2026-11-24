package com.checkout.engine.service;

import com.checkout.engine.dto.CreateProductRequest;
import com.checkout.engine.dto.ProductDetailResponse;
import com.checkout.engine.dto.ProductResponse;
import com.checkout.engine.entity.Inventory;
import com.checkout.engine.entity.Product;
import com.checkout.engine.exception.DuplicateRequestException;
import com.checkout.engine.exception.ResourceNotFoundException;
import com.checkout.engine.repository.InventoryRepository;
import com.checkout.engine.repository.ProductRepository;
import com.checkout.engine.service.cache.CacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final CacheService cacheService;

    @Value("${app.cache.product-ttl-seconds:600}")
    private long productTtlSeconds;

    @Transactional
    public ProductDetailResponse createProduct(CreateProductRequest request) {
        if (productRepository.existsBySku(request.getSku())) {
            throw new DuplicateRequestException(
                    String.format("Product with SKU '%s' already exists", request.getSku())
            );
        }

        Product product = Product.builder()
                .sku(request.getSku())
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice())
                .status("ACTIVE")
                .build();
        Product savedProduct = productRepository.save(product);

        Inventory inventory = Inventory.builder()
                .product(savedProduct)
                .availableQty(request.getInitialStock())
                .reservedQty(0)
                .build();
        Inventory savedInventory = inventoryRepository.save(inventory);

        cacheService.evict("product:" + savedProduct.getId());
        return mapToProductDetailResponse(savedProduct, savedInventory);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> getProducts(Pageable pageable) {
        return productRepository.findAll(pageable)
                .map(this::mapToProductResponse);
    }

    @Transactional(readOnly = true)
    public ProductDetailResponse getProductById(Long id) {
        String cacheKey = "product:" + id;
        return cacheService.get(cacheKey, ProductDetailResponse.class)
                .orElseGet(() -> {
                    Product product = productRepository.findById(id)
                            .orElseThrow(() -> new ResourceNotFoundException("Product", "id", id));

                    Inventory inventory = inventoryRepository.findByProductId(product.getId())
                            .orElse(null);

                    ProductDetailResponse response = mapToProductDetailResponse(product, inventory);
                    cacheService.put(cacheKey, response, Duration.ofSeconds(productTtlSeconds));
                    return response;
                });
    }

    private ProductResponse mapToProductResponse(Product product) {
        return ProductResponse.builder()
                .id(product.getId())
                .sku(product.getSku())
                .name(product.getName())
                .description(product.getDescription())
                .price(product.getPrice())
                .status(product.getStatus())
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .build();
    }

    private ProductDetailResponse mapToProductDetailResponse(Product product, Inventory inventory) {
        return ProductDetailResponse.builder()
                .id(product.getId())
                .sku(product.getSku())
                .name(product.getName())
                .description(product.getDescription())
                .price(product.getPrice())
                .status(product.getStatus())
                .availableQty(inventory != null ? inventory.getAvailableQty() : 0)
                .reservedQty(inventory != null ? inventory.getReservedQty() : 0)
                .version(inventory != null ? inventory.getVersion() : null)
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .build();
    }
}
