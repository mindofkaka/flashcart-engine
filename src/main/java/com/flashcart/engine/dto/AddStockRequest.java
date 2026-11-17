package com.flashcart.engine.dto;

import jakarta.validation.constraints.Min;

public record AddStockRequest(@Min(1) int quantity) {}
