package com.seat.backend.reservation;

import com.seat.backend.event.Seat;
import com.seat.backend.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Reservation {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** User ↔ Reservation = 1 : N (단방향) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Seat ↔ Reservation = 1 : N (활성 기준 1:1, 이력 누적되면 N) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = ReservationStatus.HOLD;
    }

    public void confirm() {
        if (this.status != ReservationStatus.HOLD) {
            throw new IllegalStateException("HOLD 예약만 확정할 수 있습니다.");
        }
        this.status = ReservationStatus.RESERVED;
    }

    public void cancel() {
        this.status = ReservationStatus.CANCELLED;
    }

    public boolean isExpired() {
        return status == ReservationStatus.HOLD && expiresAt.isBefore(LocalDateTime.now());
    }
}