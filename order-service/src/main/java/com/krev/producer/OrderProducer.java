package com.krev.producer;

import com.krev.order.contract.OrderCreatedEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class OrderProducer {
    private final JmsTemplate jmsTemplate;

    @Value("${messaging.topics.orders}")
    private String topicName;

    public OrderProducer(@Qualifier("topicJmsTemplate") JmsTemplate jmsTemplate) {
        this.jmsTemplate = jmsTemplate;
    }

    public void send(OrderCreatedEvent orderCreatedEvent) {
        boolean cheap = orderCreatedEvent.price().compareTo(BigDecimal.valueOf(1000)) < 0;
        jmsTemplate.convertAndSend(topicName, orderCreatedEvent, message -> {
            message.setStringProperty("notificationType", cheap ? "LOW_PRICE" : "HIGH_PRICE");
            return message;
        });
    }
}
