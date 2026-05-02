package com.seat.backend.event;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long> {
    List<Event> findByStartAtAfterOrderByStartAtAsc(LocalDateTime now);
}