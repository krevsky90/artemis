package com.krev.producer;

import com.krev.entity.Order;
import com.krev.event.OrderCreatedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class OrderProducer {
    private final JmsTemplate jmsTemplate;

    @Value("${messaging.queues.orders}")
    private String queueName;

    public OrderProducer(JmsTemplate jmsTemplate) {
        this.jmsTemplate = jmsTemplate;
    }

    public void send(OrderCreatedEvent orderCreatedEvent) {
        jmsTemplate.convertAndSend(queueName, orderCreatedEvent);
    }
}
