package com.krev.consumer;

import com.krev.exception.MailSendException;
import com.krev.order.contract.OrderCreatedEvent;
import com.krev.producer.RetryProducer;
import com.krev.service.EmailService;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class NotificationConsumer {
    private final EmailService emailService;
    private final RetryProducer retryProducer;

    public NotificationConsumer(EmailService emailService, RetryProducer retryProducer) {
        this.emailService = emailService;
        this.retryProducer = retryProducer;
    }

    @JmsListener(destination = "${messaging.queues.orders}",
//            subscription = "${messaging.subscriptions.notification}",
            containerFactory = "queueListenerFactory")
    public void consume(OrderCreatedEvent event, Message message) throws InterruptedException, JMSException {
        log.info("NotificationConsumer has received eventId = {} with product = {}", event.eventId(), event.product());
        try {
            emailService.send(event);
            log.info("NotificationConsumer: emailService.send({}) is successfully executed", event.eventId());
        } catch (MailSendException ex) {
            log.warn("NotificationConsumer: emailService.send({}) has failed due to error = {}", event.eventId(), ex.getMessage());
            retryProducer.retry(event, message);
        }
//        log.info("JMSTimestamp={}", message.getJMSTimestamp());
//        log.info("JMSExpiration={}", message.getJMSExpiration());
//        log.info("JMSPriority={}", message.getJMSPriority());
//        Thread.sleep(3000);
    }
}
