package com.krev.controller;

import com.krev.order.contract.OrderCreatedEvent;
import jakarta.validation.Valid;
import com.krev.dto.OrderRequest;
import com.krev.producer.OrderProducer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderProducer orderProducer;

    public OrderController(OrderProducer orderProducer) {
        this.orderProducer = orderProducer;
    }

    @PostMapping
    public ResponseEntity<Void> createOrder(@Valid @RequestBody OrderRequest orderRequest) {
        UUID orderId = UUID.randomUUID();

        OrderCreatedEvent event = new OrderCreatedEvent(
                UUID.randomUUID(),
                orderId,
                orderRequest.product(),
                orderRequest.price(),
                Instant.now()
        );

        orderProducer.send(event);

        return ResponseEntity.accepted().build();
    }
}
