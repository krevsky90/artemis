package com.krev.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.krev.order.contract.OrderCreatedEvent;
import jakarta.jms.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.support.converter.MappingJackson2MessageConverter;
import org.springframework.jms.support.converter.MessageConverter;

import java.util.Map;

import static org.springframework.jms.support.converter.MessageType.TEXT;

@Configuration
public class JmsConfig {
    //this converter is needed to convert JSON artemis message -> jackson -> Order
    @Bean
    public MessageConverter jacksonJmsMessageConverter(ObjectMapper objectMapper) {
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();

        converter.setObjectMapper(objectMapper);
        converter.setTargetType(TEXT);

        // statement 'converter.setTypeIdPropertyName("_type"); adds field  "_type": "com.krev.entity.Order" to JSON message
        //BUT potential problem is the situation when Order
        //to make to more flexible - use setTypeIdMappings also (where 'order-created' is value of '_type")
        converter.setTypeIdPropertyName("_type");
        converter.setTypeIdMappings(
                Map.of("order-created", OrderCreatedEvent.class)
        );

        return converter;
    }

    @Bean
    public JmsTemplate topicJmsTemplate(ConnectionFactory connectionFactory, MessageConverter converter) {
        JmsTemplate template = new JmsTemplate(connectionFactory);

        template.setMessageConverter(converter);

        // JMS template for publishing messages to Topics (not Queue!)
        template.setPubSubDomain(true);

        return template;
    }

    //for any case (if we want to publish to queue, but not topic)
    // BUT NOW this is identical to default existing auto-configurable bean

//    @Bean
//    public JmsTemplate queueJmsTemplate(ConnectionFactory connectionFactory, MessageConverter converter) {
//        JmsTemplate template = new JmsTemplate(connectionFactory);
//
//        template.setMessageConverter(converter);
//
//        // JMS template for publishing messages to Topics (not Queue!)
//        template.setPubSubDomain(false);
//
//        return template;
//    }
}