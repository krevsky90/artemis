package com.krev.service;

import com.krev.exception.MailSendException;
import com.krev.order.contract.OrderCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class EmailService {
    public void send(OrderCreatedEvent event) throws MailSendException {
        throw new MailSendException("Email Service is unavailable");
    }
}
