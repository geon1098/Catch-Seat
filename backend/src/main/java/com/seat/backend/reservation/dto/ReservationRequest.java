package com.seat.backend.reservation.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Getter
public class ReservationRequest {
    @NotNull private Long seatId;
}