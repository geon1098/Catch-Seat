package com.seat.backend.reservation;

import com.seat.backend.event.SeatRepository;
import com.seat.backend.event.SeatStatus;
import com.seat.backend.user.User;
import com.seat.backend.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ReservationConcurrencyTest {

    @Autowired ReservationService reservationService;
    @Autowired ReservationRepository reservationRepo;
    @Autowired SeatRepository seatRepo;
    @Autowired UserRepository userRepo;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    private static final Long SEAT_ID = 1L;
    private static final int THREADS = 100;

    private Long testUserId;

    /**
     * 매 테스트 전 상태를 깨끗하게 리셋한다.
     * - 좌석 1번에 걸려 있던 기존 예약 삭제
     * - 좌석 1번 상태를 AVAILABLE 로 되돌림
     * - 테스트용 유저 1명을 보장 (lucky 한 1명이 INSERT 할 때 FK 만족용)
     */
    @BeforeEach
    void setUp() {
        // Spring Test 의 @Transactional 은 @Test 메서드에 붙었을 때만 자동으로
        // 트랜잭션을 시작하고 @BeforeEach 까지 전파한다. @BeforeEach 에만 붙이면
        // 트랜잭션이 시작되지 않아 em.executeUpdate() 가 TransactionRequiredException 으로 실패.
        // → TransactionTemplate 으로 명시적으로 트랜잭션을 연다 (커밋되어야 본 테스트가 본다).
        new TransactionTemplate(txManager).execute(status -> {
            // 1) 이전 테스트가 남긴 예약 제거 (FK 때문에 reservations 부터)
            em.createQuery("delete from Reservation r where r.seat.id = :sid")
                    .setParameter("sid", SEAT_ID)
                    .executeUpdate();

            // 2) 좌석 상태를 AVAILABLE 로 리셋 (version 도 0 으로 되돌려 낙관적 락 검증을 깔끔히)
            seatRepo.findById(SEAT_ID).orElseThrow(() -> new IllegalStateException(
                    "seatId=1 좌석이 없습니다. db/init.sql 의 시드 데이터가 들어갔는지 확인하세요."));
            em.createQuery("update Seat s set s.status = :st, s.version = 0 where s.id = :id")
                    .setParameter("st", SeatStatus.AVAILABLE)
                    .setParameter("id", SEAT_ID)
                    .executeUpdate();

            // 3) 테스트용 유저 1명 보장 — 모든 스레드가 이 유저로 시도한다.
            //    어차피 좌석 1개에 100명이 몰려도 성공하는 건 1명뿐이라
            //    INSERT 까지 가는 스레드도 1개뿐. userId 가 같아도 무방.
            User u = userRepo.save(User.builder()
                    .email("loadtest-" + UUID.randomUUID() + "@example.com")
                    .password("$2a$10$dummyhashdummyhashdummyhashdummyhashdummyhashdumm")
                    .nickname("loadtest")
                    .role("ROLE_USER")
                    .build());
            testUserId = u.getId();
            return null;
        });
    }

    /**
     * 비관적 락(SELECT ... FOR UPDATE) 버전.
     *
     * 100개의 스레드가 같은 좌석을 동시에 예약 시도해도, DB 가
     * 행에 X-Lock 을 걸어 한 번에 한 트랜잭션만 통과시킨다.
     * → 정확히 1명만 성공하고 나머지 99명은 SEAT_NOT_AVAILABLE 예외로 실패해야 한다.
     */
    @Test
    void 같은_좌석에_100명이_동시_예약하면_1명만_성공해야_한다() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(THREADS);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail    = new AtomicInteger();

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    reservationService.reserveWithPessimistic(testUserId, SEAT_ID);
                    success.incrementAndGet();
                } catch (Exception e) {
                    fail.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        System.out.printf("success=%d, fail=%d%n", success.get(), fail.get());

        assertThat(success.get()).isEqualTo(1);
        assertThat(fail.get()).isEqualTo(99);

        // DB 에도 정확히 1건의 예약만 남아 있어야 한다.
        long resvForSeat = reservationRepo.findAll().stream()
                .filter(r -> r.getSeat().getId().equals(SEAT_ID))
                .count();
        assertThat(resvForSeat).isEqualTo(1);
    }
}
