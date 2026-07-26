package com.krev.consumer;

import com.krev.order.contract.OrderCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class HighPriceNotificationConsumer {
    @JmsListener(destination = "${messaging.topics.orders}",
//            subscription = "high-price-subscription",
            subscription = "${messaging.subscriptions.notification}",
//            selector = "notificationType = 'HIGH_PRICE'",
            containerFactory = "topicListenerFactory")
    public void consume(OrderCreatedEvent event) {
        log.info("HighPriceNotificationConsumer has received event = {}", event);
    }
}
