package com.aporkolab.demo.order;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = """
            SELECT e FROM OutboxEvent e 
            WHERE e.status = 'PENDING' 
            ORDER BY e.createdAt ASC 
            LIMIT 100
            """)
    List<OutboxEvent> findPendingEventsForUpdate();

    @Query("SELECT COUNT(e) FROM OutboxEvent e WHERE e.status = 'PENDING'")
    long countPendingEvents();
}
