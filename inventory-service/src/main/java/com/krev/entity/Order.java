package com.krev.entity;

import java.math.BigDecimal;
import java.util.UUID;

public record Order(UUID id, String product, BigDecimal price) {
}
