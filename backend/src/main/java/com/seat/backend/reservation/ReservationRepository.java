package com.seat.backend.reservation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findBySeat_IdAndStatus(Long seatId, ReservationStatus status);

    /** 만료 보강 스케줄러용 — 좌석을 fetch join 으로 같이 가져와 N+1 회피 */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"seat"})
    List<Reservation> findAllByStatusAndExpiresAtBefore(
            ReservationStatus status, LocalDateTime cutoff);
}