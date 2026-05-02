package com.seat.backend.reservation;

import com.seat.backend.auth.AuthUser;
import com.seat.backend.common.ApiResponse;
import com.seat.backend.reservation.dto.ReservationRequest;
import com.seat.backend.reservation.dto.ReservationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

//    @PostMapping
//    public ApiResponse<ReservationResponse> reserve(@RequestBody @Valid ReservationRequest req) {
//        Long userId = AuthUser.currentUserId();
//        return ApiResponse.ok(reservationService.reserveNoLock(userId, req.getSeatId()));
//    }
    //비관적 락 버전으로 교체
    @PostMapping
    public ApiResponse<ReservationResponse> reserve(@RequestBody @Valid ReservationRequest req) {
        Long userId = AuthUser.currentUserId();
        return ApiResponse.ok(reservationService.reserveWithPessimistic(userId, req.getSeatId()));
    }
}