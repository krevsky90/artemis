package com.krev.consumer;

import com.krev.order.contract.OrderCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class NotificationConsumer {
    @JmsListener(destination = "${messaging.topics.orders}",
            subscription = "${messaging.subscriptions.notification}",
            containerFactory = "topicListenerFactory")
    public void consume(OrderCreatedEvent event) {
        log.info("NotificationConsumer has received event = {}", event);
    }
}
