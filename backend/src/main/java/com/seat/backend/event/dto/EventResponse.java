package com.seat.backend.event.dto;

import com.seat.backend.event.Event;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class EventResponse {
    private Long id;
    private String title;
    private LocalDateTime startAt;

    public static EventResponse from(Event e) {
        return new EventResponse(e.getId(), e.getTitle(), e.getStartAt());
    }
}