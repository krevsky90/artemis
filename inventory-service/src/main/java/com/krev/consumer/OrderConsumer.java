package com.krev.consumer;

import com.krev.order.contract.OrderCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class OrderConsumer {

    @JmsListener(destination = "${messaging.queues.orders}")
    public void consume(OrderCreatedEvent event) throws InterruptedException {
        String thread = Thread.currentThread().getName();

        System.out.println("New order created: " + event);

        log.info("Thread={} received order={}", thread, event.orderId());
        processInventory(event);

        Thread.sleep(3000);

        log.info("Thread={} finished order={}", thread, event.orderId());
    }

    private void processInventory(OrderCreatedEvent event) {
        System.out.println("Received product: " + event.product());
    }
}
