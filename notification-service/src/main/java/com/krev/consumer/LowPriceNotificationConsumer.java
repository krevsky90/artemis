package com.krev.consumer;

import com.krev.order.contract.OrderCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class LowPriceNotificationConsumer {
    /**
     * temporary turn off consumer
     */
//    @JmsListener(destination = "${messaging.topics.orders}",
//            subscription = "low-price-subscription",
//            selector = "notificationType = 'LOW_PRICE'",
//            containerFactory = "topicListenerFactory")
//    public void consume(OrderCreatedEvent event) {
//        log.info("LowPriceNotificationConsumer has received event = {}", event);
//    }
}
