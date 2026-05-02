package com.seat.backend.reservation;

import com.seat.backend.common.BusinessException;
import com.seat.backend.common.ErrorCode;
import com.seat.backend.event.Seat;
import com.seat.backend.event.SeatRepository;
import com.seat.backend.event.SeatStatus;
import com.seat.backend.reservation.dto.ReservationResponse;
import com.seat.backend.user.User;
import com.seat.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final UserRepository userRepo;
    private final SeatRepository seatRepo;
    private final ReservationRepository resvRepo;

    @Value("${app.reservation.hold-seconds}")
    private long holdSeconds;

    /** 락 없는 예약 — 동시성 문제 재현용 */
    @Transactional
    public ReservationResponse reserveNoLock(Long userId, Long seatId) {
        Seat seat = seatRepo.findById(seatId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND));

        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            throw new BusinessException(ErrorCode.SEAT_NOT_AVAILABLE);
        }

        // ↑↑↑ 이 사이에서 다른 트랜잭션이 같은 좌석을 읽으면 둘 다 통과 ↑↑↑

        seat.hold();   // status = HOLD

        // getReferenceById : DB 조회 없이 프록시만 만들어 FK 만 채움 — 가장 가벼운 참조
        User userRef = userRepo.getReferenceById(userId);

        Reservation r = resvRepo.save(Reservation.builder()
                .user(userRef)
                .seat(seat)
                .status(ReservationStatus.HOLD)
                .expiresAt(LocalDateTime.now().plusSeconds(holdSeconds))
                .build());

        return ReservationResponse.from(r);
    }
    
    @Transactional
    public ReservationResponse reserveWithPessimistic(Long userId, Long seatId) {
        // FOR UPDATE — 같은 좌석을 노리는 다른 트랜잭션은 여기서 대기
        Seat seat = seatRepo.findByIdForUpdate(seatId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND));

        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            throw new BusinessException(ErrorCode.SEAT_NOT_AVAILABLE);
        }

        seat.hold();

        User userRef = userRepo.getReferenceById(userId);
        Reservation r = resvRepo.save(Reservation.builder()
                .user(userRef)
                .seat(seat)
                .status(ReservationStatus.HOLD)
                .expiresAt(LocalDateTime.now().plusSeconds(holdSeconds))
                .build());

        return ReservationResponse.from(r);
    }
}