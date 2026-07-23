package com.krev.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krev.order.contract.OrderCreatedEvent;
import jakarta.jms.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.support.converter.MappingJackson2MessageConverter;
import org.springframework.jms.support.converter.MessageConverter;

import java.util.Map;

import static org.springframework.jms.support.converter.MessageType.TEXT;

//like JmsConfig in order-service
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
    public DefaultJmsListenerContainerFactory topicListenerFactory(ConnectionFactory connectionFactory, MessageConverter converter) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();

        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(converter);

        // turn on JMS transactions
        factory.setSessionTransacted(true);

        // Container factory for Topic listeners (not Queue listeners!)
        factory.setPubSubDomain(true);

        return factory;
    }

    @Bean
    public DefaultJmsListenerContainerFactory queueListenerFactory(ConnectionFactory connectionFactory, MessageConverter converter) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();

        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(converter);

        // turn on JMS transactions
        factory.setSessionTransacted(true);

        // Container factory for Queue listeners
        // NOTE: by default PubSubDomain = false, so this code is not necessary
        factory.setPubSubDomain(false);

        return factory;
    }
}
