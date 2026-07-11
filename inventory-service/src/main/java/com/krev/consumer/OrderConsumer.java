package com.krev.consumer;

import com.krev.event.OrderCreatedEvent;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

@Component
public class OrderConsumer {

    @JmsListener(destination = "${messaging.queues.orders}")
    public void consume(OrderCreatedEvent event) {
        System.out.println("New order created: " + event);
        processInventory(event);
    }

    private void processInventory(OrderCreatedEvent event) {
        System.out.println("Received product: " + event.product());
    }
}
