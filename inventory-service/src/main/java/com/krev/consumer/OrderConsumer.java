package com.krev.consumer;

import com.krev.order.contract.OrderCreatedEvent;
import com.krev.service.OrderProcessor;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class OrderConsumer {
    private final OrderProcessor orderProcessor;

    public OrderConsumer(OrderProcessor orderProcessor) {
        this.orderProcessor = orderProcessor;
    }

    @JmsListener(destination = "${messaging.topics.orders}",
//            subscription = "${messaging.subscriptions.inventory}",
            containerFactory = "topicListenerFactory")
    public void consume(OrderCreatedEvent event, Message message) throws InterruptedException, JMSException {
        log.info("OrderConsumer has received event = {}", event);
        String thread = Thread.currentThread().getName();
        log.info("Thread={} received order={}", thread, event.orderId());
        orderProcessor.process(event);
        log.info("Thread={} finished order={}", thread, event.orderId());
    }
}
