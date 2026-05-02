package com.seat.backend.event;

public enum SeatStatus {
    AVAILABLE,   // 누구나 예약 가능
    HOLD,        // 누군가 선점, 결제 대기
    RESERVED,    // 결제 완료
    CANCELLED    // 취소
}