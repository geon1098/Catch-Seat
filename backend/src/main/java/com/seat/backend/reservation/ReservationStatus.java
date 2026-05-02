package com.seat.backend.reservation;

public enum ReservationStatus {
    HOLD,        // 결제 대기
    RESERVED,    // 결제 완료
    CANCELLED    // 취소(만료/실패)
}