package com.krev.consumer;

import com.krev.order.contract.OrderCreatedEvent;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class NotificationConsumer {
    @JmsListener(destination = "${messaging.queues.orders}",
//            subscription = "${messaging.subscriptions.notification}",
            containerFactory = "queueListenerFactory")
    public void consume(OrderCreatedEvent event, Message message) throws JMSException {
        log.info("NotificationConsumer has received eventId = {} with product = {}", event.eventId(), event.product());
        log.info("JMSTimestamp={}", message.getJMSTimestamp());
        log.info("JMSExpiration={}", message.getJMSExpiration());
        log.info("deliveryCount={}", message.getIntProperty("JMSXDeliveryCount"));

//        throw new JMSException("boom");
    }
}
