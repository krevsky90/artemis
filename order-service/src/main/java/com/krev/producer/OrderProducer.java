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
            long delay = 0L;
            if ("KREV_PRODUCT_1".equalsIgnoreCase(orderCreatedEvent.product())
                    && BigDecimal.valueOf(100.00).compareTo(orderCreatedEvent.price()) == 0) {
                    delay = 15_000L;
            } else if ("KREV_PRODUCT_2".equalsIgnoreCase(orderCreatedEvent.product())) {
                delay = 30_000L;
            }

            message.setLongProperty("_AMQ_SCHED_DELIVERY", System.currentTimeMillis() + delay);
            message.setStringProperty("JMSXGroupID", orderCreatedEvent.product());

            return message;
        });
    }
}
