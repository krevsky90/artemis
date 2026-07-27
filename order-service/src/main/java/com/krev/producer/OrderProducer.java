package com.krev.producer;

import com.krev.order.contract.OrderCreatedEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

@Service
public class OrderProducer {
    private final JmsTemplate jmsTemplate;

    @Value("${messaging.queues.orders}")
    private String queueName;

    public OrderProducer(@Qualifier("queueJmsTemplate") JmsTemplate jmsTemplate) {
        this.jmsTemplate = jmsTemplate;
    }

    public void send(OrderCreatedEvent orderCreatedEvent) {
//        boolean cheap = orderCreatedEvent.price().compareTo(BigDecimal.valueOf(1000)) < 0;
        jmsTemplate.convertAndSend(queueName, orderCreatedEvent, message -> {
            message.setStringProperty("JMSXGroupID", orderCreatedEvent.product());
            return message;
        });
    }
}
