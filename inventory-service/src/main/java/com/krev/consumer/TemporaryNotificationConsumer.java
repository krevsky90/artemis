package com.krev.consumer;

import com.krev.order.contract.OrderCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class TemporaryNotificationConsumer {
    @JmsListener(destination = "${messaging.subscriptions.notification}", containerFactory = "queueListenerFactory")
    public void consume(OrderCreatedEvent event) {
        log.info("TemporaryNotificationConsumer has received event = {}", event);
    }
}
