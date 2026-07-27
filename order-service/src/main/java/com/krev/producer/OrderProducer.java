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
            long delay = switch (orderCreatedEvent.product()) {
                case "KREV_PRODUCT_1" -> 5_000L;
                case "KREV_PRODUCT_2" -> 10_000L;
                case "KREV_PRODUCT_3" -> 20_000L;
                default -> 0L;
            };
//            message.setStringProperty("JMSXGroupID", orderCreatedEvent.product());

            message.setLongProperty("_AMQ_SCHED_DELIVERY", System.currentTimeMillis() + delay);
            return message;
        });
    }
}
