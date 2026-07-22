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

    @JmsListener(destination = "${messaging.queues.orders}")
    public void consume(OrderCreatedEvent event, Message message) throws InterruptedException, JMSException {
        String thread = Thread.currentThread().getName();

//        log.info("============== Main message properties/headers: ==============");
//        log.info("MessageId={}", message.getJMSMessageID());
//        log.info("CorrelationId={}", message.getJMSCorrelationID());
//        log.info("Redelivered={}", message.getJMSRedelivered());
//        log.info("DeliveryCount={}", message.getIntProperty("JMSXDeliveryCount"));
//        log.info("ReplyTo={}", message.getJMSReplyTo());
//        log.info("Priority={}", message.getJMSPriority());
//        log.info("Expiration={}", message.getJMSExpiration());
//        log.info("Destination={}", message.getJMSDestination());
//        log.info("Timestamp={}", message.getJMSTimestamp());

//        System.out.println("New order created: " + event);

        log.info("Thread={} received order={}", thread, event.orderId());

//        throw new RuntimeException("Inventory service failed");
        orderProcessor.process(event);

//        Thread.sleep(3000);

        log.info("Thread={} finished order={}", thread, event.orderId());
    }


}
