package com.flashcart.engine.controller;

import com.flashcart.engine.dto.*;
import com.flashcart.engine.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final PurchaseService purchaseService;
    private final StockAdminService stockAdminService;
    public record PurchaseAccepted(java.util.UUID orderId, String status) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(@Valid @RequestBody CreateProductRequest request) {
        return ProductResponse.from(productService.create(request));
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable Long id) {
        return ProductResponse.from(productService.get(id));
    }

    @PostMapping("/{id}/purchase")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PurchaseAccepted purchase(@PathVariable Long id, @Valid @RequestBody PurchaseRequest request) {
        return new PurchaseAccepted(purchaseService.purchase(id, request.quantity()), "PROCESSING");
    }

    @PostMapping("/{id}/stock")
    public ProductResponse addStock(@PathVariable Long id, @Valid @RequestBody AddStockRequest request) {
        stockAdminService.addStock(id, request.quantity());
        return ProductResponse.from(productService.get(id));
    }
}