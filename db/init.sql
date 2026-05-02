USE seatapp;

-- ============================================================
-- 1. users
-- ============================================================
CREATE TABLE IF NOT EXISTS users (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    email       VARCHAR(100) NOT NULL,
    password    VARCHAR(100) NOT NULL,
    nickname    VARCHAR(50)  NOT NULL,
    role        VARCHAR(20)  NOT NULL DEFAULT 'ROLE_USER',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 2. events (공연)
-- ============================================================
CREATE TABLE IF NOT EXISTS events (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    title       VARCHAR(200) NOT NULL,
    start_at    DATETIME     NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_events_start_at (start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 3. seats (좌석) — 핵심 테이블
-- ============================================================
CREATE TABLE IF NOT EXISTS seats (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id    BIGINT       NOT NULL,
    seat_no     VARCHAR(20)  NOT NULL,                       -- A1, A2 …
    status      VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE',   -- AVAILABLE/HOLD/RESERVED/CANCELLED
    version     BIGINT       NOT NULL DEFAULT 0,             -- 낙관적 락
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_seats_event FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE,
    UNIQUE KEY uk_seats_event_no (event_id, seat_no),        -- 같은 공연에 중복 좌석 불가
    INDEX idx_seats_event_status (event_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 4. reservations (예약)
-- ============================================================
CREATE TABLE IF NOT EXISTS reservations (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    seat_id     BIGINT       NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'HOLD',        -- HOLD/RESERVED/CANCELLED
    expires_at  DATETIME     NOT NULL,                       -- HOLD 만료 시각
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_resv_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_resv_seat FOREIGN KEY (seat_id) REFERENCES seats(id),
    INDEX idx_resv_user (user_id),
    INDEX idx_resv_seat_status (seat_id, status),
    INDEX idx_resv_expires (expires_at, status)              -- 만료 스캔용
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 5. payments (결제)
-- ============================================================
CREATE TABLE IF NOT EXISTS payments (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    reservation_id  BIGINT       NOT NULL,
    amount          INT          NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'REQUESTED', -- REQUESTED/SUCCESS/FAILED
    paid_at         DATETIME     NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_pay_resv FOREIGN KEY (reservation_id) REFERENCES reservations(id),
    UNIQUE KEY uk_pay_resv (reservation_id)                    -- 한 예약당 결제 1건
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 시드 데이터 — 공연 1개 + 좌석 10개
-- ============================================================
INSERT INTO events (title, start_at) VALUES ('재즈 공연 — Spring Night', '2026-06-01 20:00:00');

INSERT INTO seats (event_id, seat_no) VALUES
 (1,'A1'),(1,'A2'),(1,'A3'),(1,'A4'),(1,'A5'),
 (1,'B1'),(1,'B2'),(1,'B3'),(1,'B4'),(1,'B5');