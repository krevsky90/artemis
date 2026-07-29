package com.krev.producer;

import com.krev.order.contract.OrderCreatedEvent;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class RetryProducer {
    private final JmsTemplate jmsTemplate;

    @Value("${messaging.queues.orders}")
    private String queue;

    public RetryProducer(JmsTemplate jmsTemplate) {
        this.jmsTemplate = jmsTemplate;
    }

    public void retry(OrderCreatedEvent event, Message initMessage) throws JMSException {
        //if the property does not exist, getIntProperty returns 0, but it is better to write
        int retry = initMessage.propertyExists("retryCount") ? initMessage.getIntProperty("retryCount") : 0;
        if (retry >= 5) {
            log.error("Retry limit exceeded");
            return;
        }

        jmsTemplate.convertAndSend(queue, event, message -> {
            message.setLongProperty("_AMQ_SCHED_DELIVERY", System.currentTimeMillis() + 10_000);
            message.setIntProperty("retryCount", retry + 1);
            return message;
        });
    }
}
