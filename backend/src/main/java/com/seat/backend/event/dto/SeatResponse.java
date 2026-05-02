package com.seat.backend.event.dto;

import com.seat.backend.event.Seat;
import com.seat.backend.event.SeatStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SeatResponse {
    private Long id;
    private String seatNo;
    private SeatStatus status;

    public static SeatResponse from(Seat s) {
        return new SeatResponse(s.getId(), s.getSeatNo(), s.getStatus());
    }
}