package com.krev.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "orderId")
@ToString
@Entity
@Table(name = "inventory", schema = "public")
public class InventoryReservation {
    @Id
    @Column(nullable = false, unique = true, name = "order_id")
    private UUID orderId;

    @Column(nullable = false, name = "product")
    private String product;

    @Column(nullable = false, name = "price")
    private BigDecimal price;

    @Column(nullable = false, name = "created_at")
    private Instant createdAt;
}
