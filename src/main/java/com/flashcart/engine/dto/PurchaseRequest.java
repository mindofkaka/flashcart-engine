package com.flashcart.engine.dto;

import jakarta.validation.constraints.Min;

public record PurchaseRequest(@Min(1) int quantity) {}