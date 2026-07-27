package com.krev.consumer;

import com.krev.order.contract.OrderCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class NotificationConsumer {
    @JmsListener(destination = "${messaging.queues.orders}",
//            subscription = "${messaging.subscriptions.notification}",
            containerFactory = "queueListenerFactory")
    public void consume(OrderCreatedEvent event) throws InterruptedException {
        log.info("NotificationConsumer has received eventId = {} with product = {}", event.eventId(), event.product());
        Thread.sleep(3000);
    }
}
