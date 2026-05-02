package com.seat.backend.event;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "seats",
       uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "seat_no"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Seat {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Event ↔ Seat = 1 : N (단방향, FK 는 자식인 Seat 가 보유) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(name = "seat_no", nullable = false, length = 20)
    private String seatNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatStatus status;

    /** 낙관적 락용 — Step 10 에서 사용 */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = SeatStatus.AVAILABLE;
    }

    // ===== 도메인 행위 =====
    public void hold() {
        if (this.status != SeatStatus.AVAILABLE) {
            throw new IllegalStateException("AVAILABLE 좌석만 HOLD 할 수 있습니다. 현재: " + this.status);
        }
        this.status = SeatStatus.HOLD;
    }

    public void confirm() {
        if (this.status != SeatStatus.HOLD) {
            throw new IllegalStateException("HOLD 좌석만 확정할 수 있습니다. 현재: " + this.status);
        }
        this.status = SeatStatus.RESERVED;
    }

    /** HOLD → AVAILABLE 복구 (결제 실패 / 만료) */
    public void release() {
        if (this.status != SeatStatus.HOLD) return;     // 이미 다른 상태면 그냥 두기
        this.status = SeatStatus.AVAILABLE;
    }
}