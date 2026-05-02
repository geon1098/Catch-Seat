package com.seat.backend.reservation.dto;

import com.seat.backend.reservation.Reservation;
import com.seat.backend.reservation.ReservationStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class ReservationResponse {
    private Long reservationId;
    private Long seatId;
    private ReservationStatus status;
    private LocalDateTime expiresAt;

    public static ReservationResponse from(Reservation r) {
        // r.getSeat() 는 LAZY 프록시 — getId() 만 호출하면 추가 SQL 없음 (Hibernate 최적화)
        return new ReservationResponse(r.getId(), r.getSeat().getId(), r.getStatus(), r.getExpiresAt());
    }
}