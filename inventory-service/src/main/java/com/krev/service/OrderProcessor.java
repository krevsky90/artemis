package com.krev.service;

import com.krev.model.InventoryReservation;
import com.krev.model.ProcessedEvent;
import com.krev.order.contract.OrderCreatedEvent;
import com.krev.repository.InventoryRepository;
import com.krev.repository.ProcessedEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Slf4j
public class OrderProcessor {
    private final InventoryRepository inventoryRepository;
    private final ProcessedEventRepository processedEventRepository;

    public OrderProcessor(InventoryRepository inventoryRepository, ProcessedEventRepository processedEventRepository) {
        this.inventoryRepository = inventoryRepository;
        this.processedEventRepository = processedEventRepository;
    }

    // NOTE: @Transactional is MANDATORY
    // since ProcessedEvent and InventoryReservation must be committed atomically.
    // Otherwise, the event may be marked as processed while inventory is not updated.
    @Transactional
    public void process(OrderCreatedEvent event) {
        UUID eventId = event.eventId();
        UUID orderId = event.orderId();

        //deduplication check
        try {
            processedEventRepository.save(new ProcessedEvent(eventId));
        } catch (DuplicateKeyException exception) {
            // NOTE: we use DuplicateKeyException (more concrete) instead of DataIntegrityViolationException!
            log.warn("Event {} has been already processed. Do nothing", eventId);
            return;
        }

        //save event data to inventory reservation
//        try {
        inventoryRepository.save(
                new InventoryReservation(
                        orderId,
                        event.product(),
                        event.price(),
                        event.createdAt()
                )
        );
//        } catch (Exception ex) {
//            log.error("Unable to save event {} to inventory database due to the error: {}", event, ex.getMessage());
//            throw new RuntimeException(ex);
//        }

        log.info("Processed event {}, order={}", eventId, orderId);
    }
}
