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

    @Value("${messaging.queues.orders}")
    private String queueName;

    public OrderProducer(@Qualifier("queueJmsTemplate") JmsTemplate jmsTemplate) {
        this.jmsTemplate = jmsTemplate;
    }

    public void send(OrderCreatedEvent orderCreatedEvent) {
        jmsTemplate.convertAndSend(queueName, orderCreatedEvent, message -> {
//            message.setJMSExpiration(10_000);   // does not work! will be ignored/overriden by JmsTemplate
//            message.setLongProperty("_AMQ_SCHED_DELIVERY", System.currentTimeMillis() + 30_000);
            return message;
        });
    }
}
