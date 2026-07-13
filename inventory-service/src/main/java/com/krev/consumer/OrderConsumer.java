package com.krev.consumer;

import com.krev.order.contract.OrderCreatedEvent;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class OrderConsumer {

    @JmsListener(destination = "${messaging.queues.orders}")
    public void consume(OrderCreatedEvent event, Message message) throws InterruptedException, JMSException {
        String thread = Thread.currentThread().getName();
        log.info("deliveryCount={}", message.getIntProperty("JMSXDeliveryCount"));

        System.out.println("New order created: " + event);

        log.info("Thread={} received order={}", thread, event.orderId());

        throw new RuntimeException("Inventory service failed");
//        processInventory(event);
//
//        Thread.sleep(3000);
//
//        log.info("Thread={} finished order={}", thread, event.orderId());
    }

    private void processInventory(OrderCreatedEvent event) {
        System.out.println("Received product: " + event.product());
    }
}
