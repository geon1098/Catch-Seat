package com.seat.backend.event;

import com.seat.backend.common.ApiResponse;
import com.seat.backend.event.dto.EventResponse;
import com.seat.backend.event.dto.SeatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @GetMapping //다가오는 공연 목록
    public ApiResponse<List<EventResponse>> upcoming() {
        return ApiResponse.ok(eventService.upcoming());
    }

    @GetMapping("/{eventId}/seats") //좌석 목록
    public ApiResponse<List<SeatResponse>> seats(@PathVariable("eventId") Long eventId) {
        return ApiResponse.ok(eventService.seatsOf(eventId));
    }
}