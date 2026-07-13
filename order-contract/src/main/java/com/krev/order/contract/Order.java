package com.krev.order.contract;

import java.math.BigDecimal;
import java.util.UUID;

public record Order(UUID id, String product, BigDecimal price) {
}
