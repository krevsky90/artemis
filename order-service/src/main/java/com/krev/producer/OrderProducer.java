package com.krev.producer;

import com.krev.order.contract.OrderCreatedEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

@Service
public class OrderProducer {
    private final JmsTemplate low;
    private final JmsTemplate high;

    @Value("${messaging.queues.orders}")
    private String queueName;

    public OrderProducer(@Qualifier("lowPriorityJmsTemplate") JmsTemplate low,  @Qualifier("highPriorityJmsTemplate") JmsTemplate high) {
        this.low = low;
        this.high = high;
    }

    public void send(OrderCreatedEvent event) {
        if (event.product().startsWith("KREV")) {
            sendHigh(event);
        } else {
            sendLow(event);
        }
    }

    public void sendLow(OrderCreatedEvent event) {
        low.convertAndSend(queueName, event);
    }

    public void sendHigh(OrderCreatedEvent event) {
        high.convertAndSend(queueName, event);
    }
}
