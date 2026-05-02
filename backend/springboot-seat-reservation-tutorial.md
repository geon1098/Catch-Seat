# Spring Boot 실전 백엔드 실습서 — 동시성 처리 좌석 예약 + 결제 시스템

> "따라 치기만 해도 비관적 락 + 낙관적 락 + Redis 분산 락 + TTL 만료 처리까지 갖춘 실서비스 수준의 좌석 예약 서버가 완성되는" 풀백엔드 실습서

---

## 실습 개요

이 실습서는 단순 CRUD가 아니라 **동시성·정합성·결제·만료** 가 모두 살아 있는 백엔드 시스템을 처음부터 끝까지 구축한다. 만들어질 서비스는 다음과 같다.

- **회원** : 이메일+비밀번호 회원가입 / 로그인, BCrypt 해시, JWT(Access) 인증, ROLE 기반 인가
- **공연(Event)** : 공연 등록, 날짜/시간 기반 조회, 좌석 일괄 생성
- **좌석(Seat)** : `AVAILABLE → HOLD → RESERVED` 상태 머신, 결제 실패 시 `CANCELLED` 또는 `AVAILABLE` 복구
- **예약(Reservation) — 핵심** : 동일 좌석 동시 요청 → **단 한 명만 성공**, 나머지는 `409 CONFLICT`
- **동시성 처리** : 비관적 락(`SELECT ... FOR UPDATE`) / 낙관적 락(`@Version`) / Redis 분산 락(Redisson) **3가지 모두 구현 + 비교**
- **결제(Payment)** : 결제 요청 → 성공 → `RESERVED`, 실패 → `HOLD` 해제(좌석 복구)
- **예약 만료** : Redis TTL + `@Scheduled` 보강 → 일정 시간 내 결제 안 하면 자동 취소
- **UI** : Thymeleaf 기반 시연용 화면(로그인/공연 목록/좌석 선택/예약·결제 버튼)
- **인프라** : Docker Compose 로 MySQL 8 + Redis 7 실행, Nginx + HTTPS 까지

---

## 목차

0. [Step 0 — 아키텍처 / ERD / 동시성 시나리오](#step-0--아키텍처--erd--동시성-시나리오)
1. [Step 1 — 프로젝트 세팅 (build.gradle, application.yml)](#step-1--프로젝트-세팅)
2. [Step 2 — Docker Compose 로 MySQL + Redis 띄우기 + 스키마 설계](#step-2--docker-compose-로-mysql--redis-띄우기)
3. [Step 3 — 공통 모듈 (ApiResponse / ErrorCode / 전역 예외 처리)](#step-3--공통-모듈)
4. [Step 4 — 도메인 설계 (User / Event / Seat / Reservation / Payment)](#step-4--도메인-설계)
5. [Step 5 — JWT 인증 (회원가입 / 로그인 / SecurityConfig)](#step-5--jwt-인증)
6. [Step 6 — 공연/좌석 API (등록 / 조회)](#step-6--공연좌석-api)
7. [Step 7 — 최소 UI (Thymeleaf 로 좌석 선택 화면 만들기)](#step-7--최소-ui)
8. [Step 8 — 예약 기능 기본 구현 (락 없는 버전 — 동시성 문제 재현)](#step-8--예약-기능-기본-구현)
9. [Step 9 — 동시성 처리 (1) 비관적 락](#step-9--동시성-처리-1-비관적-락)
10. [Step 10 — 동시성 처리 (2) 낙관적 락](#step-10--동시성-처리-2-낙관적-락)
11. [Step 11 — 동시성 처리 (3) Redis 분산 락](#step-11--동시성-처리-3-redis-분산-락)
12. [Step 12 — 결제 기능 (성공/실패 → 상태 전이)](#step-12--결제-기능)
13. [Step 13 — 예약 만료 (Redis TTL + Scheduler)](#step-13--예약-만료)
14. [Step 14 — 동시성 통합 테스트](#step-14--동시성-통합-테스트)
15. [Step 15 — 배포 (Dockerfile + docker-compose + EC2 + Nginx + HTTPS)](#step-15--배포)

---

# Step 0 — 아키텍처 / ERD / 동시성 시나리오

## 1. 목표

코드를 짜기 전에 **무엇을 / 왜 / 어떻게** 만들지를 한 장에 정리한다. 특히 "왜 락이 필요한가" 를 머릿속에 그림으로 새기는 것이 핵심이다.

## 2. 전체 시스템 그림

```
브라우저 (Thymeleaf 화면 + fetch)
   │  HTTPS
   ▼
Nginx (443) ── reverse proxy ──► Spring Boot (8080)
                                    ├── SecurityFilterChain
                                    │     └── JwtAuthenticationFilter
                                    ├── Controller       ← @Valid, ApiResponse
                                    ├── Service          ← @Transactional, 락 전략
                                    ├── Repository (JPA) ← Seat / Reservation / Payment
                                    └── @Scheduled       ← 만료 보강
                                          │
                          ┌───────────────┴───────────────┐
                          ▼                               ▼
                     MySQL 8 (3306)                  Redis 7 (6379)
                     ├── users                       ├── 분산 락 키
                     ├── events                      │   (lock:seat:{id})
                     ├── seats                       └── 예약 만료 TTL 키
                     ├── reservations                    (hold:seat:{id})
                     └── payments
```

## 3. ERD

```
┌──────────────┐         ┌──────────────┐         ┌──────────────┐
│   users      │         │   events     │         │   seats      │
├──────────────┤         ├──────────────┤         ├──────────────┤
│ id (PK)      │         │ id (PK)      │ 1     N │ id (PK)      │
│ email (UK)   │         │ title        │─────────│ event_id(FK) │
│ password     │         │ start_at     │         │ seat_no      │
│ nickname     │         │ created_at   │         │ status       │ ← AVAILABLE/HOLD/RESERVED/CANCELLED
│ role         │         └──────────────┘         │ version      │ ← 낙관적 락
│ created_at   │                                  │ created_at   │
└──────────────┘                                  └──────────────┘
       │  1                                              │
       │                                                 │ 1
       │  N                                              │
       ▼                                                 │ 1
┌──────────────┐         ┌──────────────┐                │
│reservations  │         │  payments    │                │
├──────────────┤         ├──────────────┤                │
│ id (PK)      │ 1     1 │ id (PK)      │                │
│ user_id (FK) │─────────│ reservation_ │                │
│ seat_id (FK) │         │   id (FK,UK) │                │
│ status       │         │ amount       │                │
│ expires_at   │         │ status       │ ← REQUESTED/SUCCESS/FAILED
│ created_at   │         │ paid_at      │                │
└──────────────┘         └──────────────┘                │
       └─────────────────────────────────────────────────┘
                              N : 1 (seats)
```

## 4. 좌석 상태 머신

```
[AVAILABLE] ──사용자가 예약 요청──► [HOLD] ──결제 성공──► [RESERVED]
     ▲                                │
     │                                ├─결제 실패──► [AVAILABLE] (다시 풀림)
     │                                │
     └─────TTL 만료(5분)──────────────┘
```

- `AVAILABLE` : 누구나 예약 가능
- `HOLD` : 누군가 선점, 결제 대기 중 (TTL 만료 시 자동 해제)
- `RESERVED` : 결제 완료, 확정
- `CANCELLED` : 취소된 좌석 (감사·통계 목적, 단순 화면에선 `AVAILABLE` 로 복구해도 됨)

## 5. 동시성 시나리오 — 왜 락이 필요한가

```
[ 시각 t=0ms ]   사용자 A : SELECT seat#10 → status=AVAILABLE
[ 시각 t=1ms ]   사용자 B : SELECT seat#10 → status=AVAILABLE
[ 시각 t=2ms ]   사용자 A : UPDATE status=HOLD WHERE id=10
[ 시각 t=3ms ]   사용자 B : UPDATE status=HOLD WHERE id=10  ← !!! 두 명 다 성공
```

DB 락 없이 두 트랜잭션이 동시에 같은 좌석을 읽으면 **둘 다 AVAILABLE 로 보고 둘 다 UPDATE 에 성공한다.** 결과적으로 같은 좌석에 예약이 2건 만들어진다 — 이게 바로 **이중 예약(double booking)** 사고의 본질이다.

이를 막는 3가지 방법:

| 방식 | 핵심 아이디어 | 라이브러리 |
|---|---|---|
| 비관적 락 | `SELECT ... FOR UPDATE` 로 행에 X-Lock — 다른 트랜잭션은 대기 | JPA `@Lock(PESSIMISTIC_WRITE)` |
| 낙관적 락 | `version` 컬럼으로 충돌 감지 — 충돌 시 `OptimisticLockException` 던지고 재시도 | JPA `@Version` |
| 분산 락 | DB 와 무관하게 Redis 키 1개로 직렬화 — 멀티 인스턴스에서도 동작 | Redisson `RLock` |

이 실습에서는 **셋 다 구현**하고 비교한다.

## 6. 기술 선택의 이유

| 기술 | 왜 이걸 쓰나 |
|---|---|
| Spring Boot 3.3 | 자동 설정, 가장 많이 쓰임 |
| **JPA 메인 + 필요 시 native query** | 락 어노테이션(`@Lock`, `@Version`)이 표준화돼 있어 동시성 학습에 최적 |
| MySQL 8 (InnoDB) | row-level lock 지원, FOR UPDATE 가 안정적 |
| Redis 7 + Redisson | 분산 락의 표준 구현체. `RLock.tryLock()` 한 줄로 끝 |
| JWT (Access only) | 이 실습에선 단순화를 위해 Refresh 생략 |
| Thymeleaf | 별도 프론트 없이 시연용 화면 출력 가능 |
| Docker Compose | MySQL + Redis 한 번에 띄우기 |

## 7. 면접 예상 질문

1. **비관적 락 vs 낙관적 락은 언제 어떤 걸 쓰나?**
   → 충돌 빈도에 따라. 좌석 예약처럼 **동시 요청이 한 행에 몰리는 핫스팟** 에는 비관적 락이 안전. 충돌이 드물고 처리량이 중요한 경우엔 낙관적 락이 유리.
2. **DB 락이 있는데 왜 Redis 분산 락이 또 필요하나?**
   → 트랜잭션 시작 전 단계에서 줄을 세우고 싶을 때(예: 외부 결제 API 호출), 또는 락 대상이 DB 행이 아닐 때(예: 사용자별 1건 제한). 또한 매우 짧게 락을 잡고 빨리 풀어 DB 락보다 throughput 이 좋을 수도 있다.
3. **Redis 분산 락의 함정은?**
   → 락 보유 중 GC 정지/네트워크 끊김으로 TTL 이 만료되면 다른 노드가 락을 가져갈 수 있다. Redisson 의 watchdog 갱신, 펜싱 토큰, 아니면 **DB 락과 병행** 으로 보강하는 패턴이 흔하다.

---

# Step 1 — 프로젝트 세팅

## 1. 목표

Spring Initializr 로 프로젝트를 만들고, `build.gradle` 의존성과 `application.yml` 환경 설정까지 끝낸다.

## 2. 폴더 구조

```
seat-platform/
├── docker/
│   └── docker-compose.yml
├── db/
│   └── init.sql
└── backend/
    ├── build.gradle
    └── src/main/...
        └── java/com/seat/backend/
            ├── SeatApplication.java
            ├── common/         ← ApiResponse, ErrorCode, GlobalExceptionHandler
            ├── config/         ← SecurityConfig, RedissonConfig
            ├── user/           ← User 도메인 (Entity, Repository, Service, Controller)
            ├── event/          ← Event/Seat 도메인
            ├── reservation/    ← 예약 + 동시성
            ├── payment/        ← 결제
            └── auth/           ← JWT, 로그인
```

```bash
mkdir seat-platform
cd seat-platform
mkdir docker db
```

## 3. Spring Initializr 로 프로젝트 생성

[start.spring.io](https://start.spring.io) 또는 IDE 의 New Spring Starter Project 사용.

| 항목 | 값 |
|---|---|
| Project | Gradle - Groovy |
| Language | Java |
| Spring Boot | 3.3.5 |
| Group | `com.seat` |
| Artifact | `backend` |
| Package | `com.seat.backend` |
| Java | 17 |

의존성: **Spring Web, Thymeleaf, Spring Security, Spring Data JPA, MySQL Driver, Spring Data Redis, Validation, Lombok, Spring Boot DevTools**.

생성된 프로젝트를 `seat-platform/backend/` 로 이동.

## 4. 전체 코드 — `backend/build.gradle`

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.3.5'
    id 'io.spring.dependency-management' version '1.1.6'
}

group = 'com.seat'
version = '0.0.1-SNAPSHOT'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

configurations {
    compileOnly { extendsFrom annotationProcessor }
}

repositories { mavenCentral() }

dependencies {
    // Web + View
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-thymeleaf'

    // Security
    implementation 'org.springframework.boot:spring-boot-starter-security'

    // JPA + MySQL
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    runtimeOnly 'com.mysql:mysql-connector-j'

    // Redis (Spring Data Redis 는 기본, 분산 락은 Redisson 사용)
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    implementation 'org.redisson:redisson-spring-boot-starter:3.27.2'

    // Validation
    implementation 'org.springframework.boot:spring-boot-starter-validation'

    // JWT (jjwt 0.12.x)
    implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
    runtimeOnly  'io.jsonwebtoken:jjwt-impl:0.12.6'
    runtimeOnly  'io.jsonwebtoken:jjwt-jackson:0.12.6'

    // Lombok
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'

    // DevTools
    developmentOnly 'org.springframework.boot:spring-boot-devtools'

    // Test
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.security:spring-security-test'
}

tasks.named('test') { useJUnitPlatform() }
```

## 5. 전체 코드 — `backend/src/main/resources/application.yml`

```yaml
spring:
  application:
    name: seat-platform

  datasource:
    url: jdbc:mysql://localhost:3306/seatapp?useSSL=false&serverTimezone=Asia/Seoul&characterEncoding=UTF-8
    username: seat
    password: seat1234
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      maximum-pool-size: 20            # 동시성 테스트 시 커넥션 모자라면 안 됨
      connection-timeout: 5000

  jpa:
    hibernate:
      ddl-auto: validate                # 스키마는 init.sql 로 관리, JPA 는 검증만
    properties:
      hibernate:
        format_sql: true
        show_sql: false
        dialect: org.hibernate.dialect.MySQLDialect

  data:
    redis:
      host: localhost
      port: 6379

  thymeleaf:
    cache: false
    prefix: classpath:/templates/
    suffix: .html

app:
  jwt:
    secret: ${JWT_SECRET:my-very-long-secret-key-for-seat-app-do-not-use-in-prod-please-change-me}
    access-expiration-minutes: 60
  reservation:
    hold-seconds: 300                  # HOLD 상태 5분 후 자동 만료

logging:
  level:
    root: INFO
    com.seat.backend: DEBUG
    org.hibernate.SQL: DEBUG

server:
  port: 8080
```

## 6. 전체 코드 — `SeatApplication.java`

`backend/src/main/java/com/seat/backend/SeatApplication.java`

```java
package com.seat.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // 만료 보강 스케줄러용
public class SeatApplication {
    public static void main(String[] args) {
        SpringApplication.run(SeatApplication.class, args);
    }
}
```

## 7. 코드 설명

- **`ddl-auto: validate`** : 스키마는 DB 쪽(`init.sql`)이 진실이고 JPA 는 엔티티와 일치하는지만 검증. 운영에서 가장 안전한 설정.
- **`maximum-pool-size: 20`** : 동시성 테스트에서 100개 스레드가 한꺼번에 트랜잭션을 돌릴 때 커넥션 부족이 병목이 되지 않도록 충분히 잡아둔다.
- **`hold-seconds: 300`** : HOLD 상태 유지 시간(초). Step 13 의 만료 처리에서 재사용된다.
- **`@EnableScheduling`** : 만료 보강 스케줄러를 켜기 위함.

## 8. 실행 결과

`./gradlew bootRun` 실행 시 DB/Redis 가 안 떠 있어 연결 에러가 날 수 있다. 다음 단계에서 인프라를 띄운다.

## 9. 핵심 개념 요약

- 의존성은 **목적별로 묶기** (Web/Security/JPA/Redis/JWT).
- 운영 환경에서는 `ddl-auto: validate` 로 두는 것이 정석.
- 시크릿/키는 환경변수로 외부 주입.

## 10. 면접 예상 질문

1. **`ddl-auto: validate` 와 `update` 의 차이는?**
   → `update` 는 엔티티에 맞춰 자동으로 ALTER 를 날린다. 학습/로컬엔 편하지만 운영에선 위험(인덱스 누락, 의도치 않은 컬럼 추가). `validate` 는 검증만, 마이그레이션은 별도 도구(Flyway/Liquibase 또는 `init.sql`)로.
2. **HikariCP `maximum-pool-size` 는 무한정 늘리면 좋은가?**
   → 아니다. DB 의 max_connections 한계, 컨텍스트 스위칭 비용, 락 대기열 폭주 등으로 오히려 느려진다. CPU 코어 수 + 디스크 IO 를 고려해 적정값(보통 10~50)을 잡는다.

---

# Step 2 — Docker Compose 로 MySQL + Redis 띄우기

## 1. 목표

Docker Compose 한 번으로 MySQL 8 + Redis 7 을 동시에 실행하고, 초기 스키마와 시드 데이터까지 적용한다.

## 2. 전체 코드 — `docker/docker-compose.yml`

```yaml
services:
  mysql:
    image: mysql:8.0
    container_name: seat-mysql
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: root1234
      MYSQL_DATABASE: seatapp
      MYSQL_USER: seat
      MYSQL_PASSWORD: seat1234
      TZ: Asia/Seoul
    ports:
      - "3306:3306"
    volumes:
      - seat-mysql-data:/var/lib/mysql
      - ../db/init.sql:/docker-entrypoint-initdb.d/01-init.sql:ro
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_unicode_ci
      - --default-time-zone=+09:00

  redis:
    image: redis:7.2-alpine
    container_name: seat-redis
    restart: unless-stopped
    ports:
      - "6379:6379"
    command: ["redis-server", "--appendonly", "yes"]
    volumes:
      - seat-redis-data:/data

volumes:
  seat-mysql-data:
  seat-redis-data:
```

## 3. 전체 코드 — `db/init.sql`

```sql
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
```

## 4. 코드 설명

### 좌석 테이블의 핵심 설계

| 컬럼 | 역할 |
|---|---|
| `status` | 좌석 상태 머신의 핵심. 모든 동시성 제어가 이 컬럼을 둘러싸고 일어남 |
| `version` | 낙관적 락(Step 10) 용. JPA `@Version` 매핑 |
| `UK(event_id, seat_no)` | 같은 공연에 동일 좌석 번호 두 개 생성 방지 |
| `idx_seats_event_status` | "이 공연의 AVAILABLE 좌석" 조회의 핵심 인덱스 |

### 예약 만료 인덱스

`idx_resv_expires (expires_at, status)` 는 스케줄러가 **"지금 시각 < expires_at 이고 status='HOLD' 인 행"** 을 빠르게 찾기 위한 것. 좌석 수가 늘어나면 이 인덱스가 없을 때 풀스캔이 발생한다.

### Redis 컨테이너의 `--appendonly yes`

Redis 의 AOF(Append Only File) 영속화 옵션. 분산 락만 쓸 거면 굳이 필요 없지만, **만료 키가 갑작스런 재시작으로 사라지면 좌석이 영원히 HOLD 에 갇히기 때문에** 켜둔다.

## 5. 실행

```bash
cd seat-platform/docker
docker compose up -d
docker logs seat-mysql -f                              # "ready for connections" 확인
docker exec -it seat-mysql mysql -useat -pseat1234 seatapp
```

```sql
SHOW TABLES;
SELECT * FROM seats;
```

```
+-------------------+
| Tables_in_seatapp |
+-------------------+
| events            |
| payments          |
| reservations      |
| seats             |
| users             |
+-------------------+
```

좌석 10개가 모두 `AVAILABLE` 인 것을 확인하면 성공.

## 6. 핵심 개념 요약

- Docker Compose 로 인프라를 코드화하면 팀원 어디서나 같은 환경.
- 인덱스는 **자주 도는 WHERE/ORDER BY** 를 보고 설계.
- 좌석의 `status + version` 조합이 동시성 제어의 핵심.

## 7. 면접 예상 질문

1. **왜 좌석 상태를 `seats.status` 한 컬럼으로 관리하나? 별도 테이블로 분리하면 안 되나?**
   → 별도 테이블로 분리하면 정합성 검증이 복잡해지고 락 범위도 넓어진다. **상태가 좌석 자체의 속성**이라면 한 행에 두는 게 자연스럽고 락도 한 행에만 잡힌다.
2. **`UNIQUE KEY uk_pay_resv` 가 왜 필요한가?**
   → 결제 API 가 중복 호출되거나 리트라이될 때 동일 예약에 결제 행이 2개 생기는 것을 DB 레벨에서 차단. 멱등성의 마지막 안전망.

---

# Step 3 — 공통 모듈

## 1. 목표

모든 API 가 일관된 응답을 갖도록 `ApiResponse`, `ErrorCode`, 비즈니스 예외, 전역 예외 처리기를 만든다.

## 2. 전체 코드 — `common/ApiResponse.java`

`backend/src/main/java/com/seat/backend/common/ApiResponse.java`

```java
package com.seat.backend.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private boolean success;
    private String  code;
    private String  message;
    private T       data;
    private LocalDateTime timestamp;

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "OK", "성공", data, LocalDateTime.now());
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, "OK", "성공", null, LocalDateTime.now());
    }

    public static <T> ApiResponse<T> error(ErrorCode ec) {
        return new ApiResponse<>(false, ec.getCode(), ec.getMessage(), null, LocalDateTime.now());
    }
}
```

## 3. 전체 코드 — `common/ErrorCode.java`

```java
package com.seat.backend.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 공통
    INVALID_INPUT     (HttpStatus.BAD_REQUEST,           "C001", "입력값이 올바르지 않습니다."),
    INTERNAL_ERROR    (HttpStatus.INTERNAL_SERVER_ERROR, "C500", "서버 오류가 발생했습니다."),

    // 인증
    UNAUTHORIZED      (HttpStatus.UNAUTHORIZED, "A001", "인증이 필요합니다."),
    INVALID_TOKEN     (HttpStatus.UNAUTHORIZED, "A002", "유효하지 않은 토큰입니다."),
    BAD_CREDENTIALS   (HttpStatus.UNAUTHORIZED, "A003", "이메일 또는 비밀번호가 일치하지 않습니다."),

    // 회원
    EMAIL_DUPLICATED  (HttpStatus.CONFLICT, "U001", "이미 사용 중인 이메일입니다."),
    USER_NOT_FOUND    (HttpStatus.NOT_FOUND, "U002", "회원을 찾을 수 없습니다."),

    // 공연/좌석
    EVENT_NOT_FOUND   (HttpStatus.NOT_FOUND, "E001", "공연을 찾을 수 없습니다."),
    SEAT_NOT_FOUND    (HttpStatus.NOT_FOUND, "E002", "좌석을 찾을 수 없습니다."),

    // 예약 — 동시성 핵심
    SEAT_NOT_AVAILABLE(HttpStatus.CONFLICT, "R001", "이미 선점된 좌석입니다."),
    LOCK_FAILED       (HttpStatus.CONFLICT, "R002", "잠시 후 다시 시도해주세요."),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "R003", "예약을 찾을 수 없습니다."),
    RESERVATION_EXPIRED  (HttpStatus.GONE,    "R004", "예약 시간이 만료되었습니다."),
    RESERVATION_FORBIDDEN(HttpStatus.FORBIDDEN,"R005", "본인의 예약만 처리할 수 있습니다."),

    // 결제
    PAYMENT_FAILED    (HttpStatus.BAD_REQUEST, "P001", "결제에 실패했습니다."),
    PAYMENT_DUPLICATED(HttpStatus.CONFLICT,    "P002", "이미 결제된 예약입니다.");

    private final HttpStatus status;
    private final String     code;
    private final String     message;
}
```

## 4. 전체 코드 — `common/BusinessException.java`

```java
package com.seat.backend.common;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
```

## 5. 전체 코드 — `common/GlobalExceptionHandler.java`

```java
package com.seat.backend.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 비즈니스 예외 — ErrorCode 의 status 코드로 응답 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        ErrorCode ec = e.getErrorCode();
        log.warn("BusinessException: {} - {}", ec.getCode(), ec.getMessage());
        return ResponseEntity.status(ec.getStatus()).body(ApiResponse.error(ec));
    }

    /** @Valid 실패 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValid(MethodArgumentNotValidException e) {
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT));
    }

    /** 낙관적 락 충돌 — 컨트롤러까지 올라온 경우 */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimistic(OptimisticLockingFailureException e) {
        log.warn("Optimistic lock 충돌: {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.SEAT_NOT_AVAILABLE.getStatus())
                .body(ApiResponse.error(ErrorCode.SEAT_NOT_AVAILABLE));
    }

    /** 그 외 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleEtc(Exception e) {
        log.error("UNCAUGHT", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR));
    }
}
```

## 6. 코드 설명

- **`ApiResponse`** : 클라이언트가 항상 `{success, code, message, data}` 4 필드를 기대할 수 있게 해 준다.
- **`ErrorCode`** 의 그룹화(`C/A/U/E/R/P`) : 공통/인증/회원/공연/예약/결제. 신입이 가장 자주 빠뜨리는 게 코드 체계다.
- **`OptimisticLockingFailureException`** 핸들러 : Step 10 의 낙관적 락이 컨트롤러까지 예외를 던져도 깔끔한 `409` 로 응답 가능.

## 7. 핵심 개념 요약

- 응답 포맷 통일 → 프론트가 `success` 만 보면 분기 가능.
- 예외 → `ErrorCode` 매핑 → HTTP status 까지 일관.
- 로깅은 `@Slf4j`, **비즈니스 예외는 warn**, 진짜 버그는 error 로 구분.

## 8. 면접 예상 질문

1. **`@RestControllerAdvice` 와 `@ControllerAdvice` 의 차이는?**
   → 전자는 `@RestController` 에만 적용되며 응답이 자동으로 JSON. 후자는 일반 컨트롤러 포함.
2. **모든 예외를 `INTERNAL_ERROR(500)` 로 묶지 않는 이유는?**
   → 클라이언트가 재시도/사용자 메시지를 분기할 수 없게 됨. **예측 가능한 실패는 4xx**, 진짜 서버 버그만 5xx 로.

---

# Step 4 — 도메인 설계 (JPA 연관관계 설계 가이드)

## 1. 목표

JPA 엔티티 5개(`User`, `Event`, `Seat`, `Reservation`, `Payment`)를 만들면서 **객체지향적 연관관계 설계** 의 원칙을 코드로 익힌다. 이 단계는 이 실습서 전체에서 가장 길고 가장 중요한 단계다.

학습 목표 :

- 엔티티 간 관계를 객체지향적으로 설계할 줄 안다
- FK 컬럼 방식과 JPA 연관관계 방식의 차이를 이해한다
- N+1 문제를 미리 인지하고 막는 코드를 짤 수 있다
- "모든 연관관계는 LAZY + 단방향 + 최소만" 의 원칙을 체화한다

## 2. 도메인 관계 한눈에 보기

```
   ┌──────────┐                              ┌──────────┐
   │   User   │                              │  Event   │
   └────┬─────┘                              └────┬─────┘
        │ 1                                       │ 1
        │                                         │
        │ N                                       │ N
        │                                    ┌────▼─────┐
        │                                    │   Seat   │
        │                                    └────┬─────┘
        │                                         │ 1  (활성 예약 기준)
        │                                         │
        │ N                                       │ N  (이력까지 누적되면)
        │            ┌──────────────┐             │
        └───────────►│ Reservation  │◄────────────┘
                     └──────┬───────┘
                            │ 1
                            │
                            │ 1
                     ┌──────▼───────┐
                     │   Payment    │
                     └──────────────┘
```

| # | 관계 | 다중성 | 방향 | 매핑 위치 |
|---|---|---|---|---|
| 1 | Event ↔ Seat | 1 : N | 단방향 (Seat → Event) | Seat 에 `@ManyToOne` |
| 2 | User ↔ Reservation | 1 : N | 단방향 (Reservation → User) | Reservation 에 `@ManyToOne` |
| 3 | Seat ↔ Reservation | 1 : N (활성만 보면 1:1) | 단방향 (Reservation → Seat) | Reservation 에 `@ManyToOne` |
| 4 | Reservation ↔ Payment | 1 : 1 | 단방향 (Payment → Reservation) | Payment 에 `@OneToOne` |

**전부 자식 → 부모 단방향, 전부 LAZY.** `@OneToMany` 컬렉션은 단 한 곳도 안 쓴다 — 이게 이 실습의 디폴트.

## 3. FK 컬럼 방식 vs JPA 연관관계 방식

같은 "한 좌석이 어느 공연에 속하는지" 를 표현하는 두 가지 방법.

### (1) FK 컬럼 방식

```java
@Column(name = "event_id", nullable = false)
private Long eventId;
```

**장점**

- 코드가 단순 — lazy/eager, fetch join 같은 함정이 원천적으로 없음
- N+1 자체가 발생할 수 없음 (조인이 없으니까)
- DTO 조회와 손발이 잘 맞음 — 통계/배치에 유리
- "ID 만 알면 충분" 한 곳에서 직관적

**단점**

- 객체 그래프 탐색 불가 — `seat.getEvent().getTitle()` 같은 게 안 됨
- 실제 Event 객체가 필요하면 매번 `eventRepo.findById(seat.getEventId())` 별도 호출
- 도메인 모델이 빈약해짐 (그냥 데이터 보따리에 가까워짐)

**언제 쓰나**

- 단순 ID 참조만 필요할 때 (예: 외부 결제사 transaction_id, 외부 사용자 식별자)
- 성능이 매우 중요한 핫스팟
- 대용량 배치 / 통계 / 리포트 조회

### (2) JPA 연관관계 방식

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "event_id", nullable = false)
private Event event;
```

**장점**

- 객체 탐색이 자연스러움 — `seat.getEvent().getTitle()` 한 줄
- DDD 스타일의 도메인 모델링과 잘 맞음
- 트랜잭션 안에서 LAZY 로 필요할 때만 SQL 실행
- JPQL 이 객체 기반으로 깔끔해짐 (`select s from Seat s where s.event.title = ...`)

**단점**

- N+1 함정 — 개발자가 인지 못 하면 즉발
- 양방향이나 cascade 같은 옵션을 잘못 쓰면 디버깅 지옥
- 영속성 컨텍스트(persistence context) 동작을 알아야 정확히 다룰 수 있음

**언제 쓰나**

- 도메인 객체로 비즈니스 로직을 표현하는 게 중요할 때
- 트랜잭션 안에서 객체 그래프 탐색이 필요할 때
- 팀이 JPA 에 익숙해 N+1 을 fetch join 으로 안전하게 막을 수 있을 때

### 이 실습서의 선택

**JPA 연관관계 방식 + 모두 단방향 + 모두 LAZY.**

이유 :

- 실무에서 가장 많이 쓰이는 형태
- "필요한 최소만" 원칙에 부합
- N+1 은 fetch join / `@EntityGraph` / DTO 조회로 명시적으로 해결 (이 절의 7번 참고)

> 단, **단순 ID 가 컬럼 1개로 끝나는 게 자연스러운 경우** 는 그냥 `Long` / `String` 으로 둔다. 모든 FK 를 무조건 `@ManyToOne` 으로 만들 필요는 없다 — 망치를 들면 다 못으로 보이는 함정.

## 4. LAZY 로딩 전략

### 4.1 무조건 LAZY

```java
// ❌ 절대 쓰지 말 것
@ManyToOne(fetch = FetchType.EAGER)
@OneToOne(fetch = FetchType.EAGER)

// ✅ 무조건 이거
@ManyToOne(fetch = FetchType.LAZY)
@OneToOne(fetch = FetchType.LAZY)
```

`@ManyToOne` / `@OneToOne` 의 **기본값이 EAGER 라는 사실** 을 잊으면 안 된다 — 명시하지 않으면 EAGER 가 박힌다. 항상 명시적으로 LAZY 를 적는다.

### 4.2 EAGER 의 4가지 함정

**(1) 즉발적 N+1**

```java
List<Reservation> all = resvRepo.findAll();
// EAGER 라면 이 한 줄에 Seat / User 까지 매번 별도 SELECT 또는 큰 조인 발생
```

**(2) 끄고 싶을 때 못 끔**

EAGER 는 필드에 박힌 어노테이션이라 모든 조회에서 항상 따라온다. "이 화면에선 필요 없는데" 하는 케이스에서 손쓸 수 없다.

**(3) fetch join 과 충돌**

EAGER 가 박혀 있으면 직접 작성한 fetch join 과 합쳐져 의도치 않은 카르테시안 곱이 생기기 쉽다.

**(4) `findAll()` = 조인 폭탄**

EAGER 관계가 2~3개 엮이면 `findAll()` 하나가 5중 조인 SQL 이 된다. 행 수가 곱해지며 폭발.

### 4.3 LAZY 가 정답인 이유

- **필요할 때만 SQL 실행** — 사용 안 하면 비용 0
- **fetch join 으로 명시적 제어 가능** — 화면별로 최적화하기 쉬움
- **테스트와 디버깅이 예측 가능** — "내가 쓴 join 이 그대로 나간다"

> **단, 트랜잭션 밖에서 LAZY 필드에 접근하면 `LazyInitializationException`.** 컨트롤러로 엔티티 자체를 노출하지 말고 항상 DTO 로 변환해 응답하는 습관이 이 함정을 막는다.

## 5. 각 엔티티별 설계와 코드

### 5.1 좌석 상태 enum — `event/SeatStatus.java`

```java
package com.seat.backend.event;

public enum SeatStatus {
    AVAILABLE, HOLD, RESERVED, CANCELLED
}
```

### 5.2 User — 루트 엔티티 (연관관계 없음)

`backend/src/main/java/com/seat/backend/user/User.java`

```java
package com.seat.backend.user;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class User {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(nullable = false, length = 100)
    private String password;

    @Column(nullable = false, length = 50)
    private String nickname;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (role == null) role = "ROLE_USER";
    }
}
```

**설계 이유**

- User 는 다른 엔티티를 참조하지 않는 **루트 엔티티**
- "이 사용자의 모든 예약" 이 필요하면 `@OneToMany List<Reservation>` 을 두고 싶어진다 — **이 유혹을 참자**

**왜 `@OneToMany List<Reservation>` 을 안 두나?**

- 사용자 한 명이 평생 예약을 수천 건 만들 수 있다 — 컬렉션에 다 들고 다닐 일이 없다
- 페이징이 안 됨 (필드 컬렉션은 전체 로딩 또는 LAZY 폭탄)
- "내 예약 목록" 은 `reservationRepo.findByUser_Id(userId, pageable)` 로 명시적 쿼리하면 됨
- 양방향이 되면 양쪽 sync 책임이 추가됨

### 5.3 Event — 루트 엔티티 (연관관계 없음)

```java
package com.seat.backend.event;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Event {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
```

**설계 이유**

- Event 는 좌석을 갖지만 `@OneToMany List<Seat>` 두지 않음 — User 와 같은 이유
- "이 공연의 좌석" 은 `seatRepo.findByEvent_Id(eventId)` 로 명시 조회

### 5.4 Seat — Event 의 자식 (`@ManyToOne`)

```java
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
```

**설계 이유**

- 한 좌석은 정확히 한 공연에 속함 → `N : 1`
- FK 는 자식(Seat) 측에서 보유 — JPA 의 일반 원칙
- LAZY — `seat.getEvent()` 호출 안 하면 SQL 안 나감

**`@JoinColumn` 옵션의 의미**

- `name = "event_id"` — 실제 DB 컬럼 이름
- `nullable = false` — DB DDL 에 NOT NULL 반영 (좌석은 항상 공연에 속해야 함)

### 5.5 Reservation — User + Seat 두 부모를 가짐

```java
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
```

```java
package com.seat.backend.reservation;

public enum ReservationStatus {
    HOLD, RESERVED, CANCELLED
}
```

**설계 이유 — Seat ↔ Reservation 의 다중성 토론**

이 부분이 실무에서 가장 헷갈리는 지점이다.

- **이론상** : 한 좌석에 활성 예약(HOLD/RESERVED)은 단 1개 → 1 : 1 처럼 보임
- **현실상** : 결제 실패 / 만료로 CANCELLED 가 누적되면 한 좌석에 예약 행이 N 개 쌓임 → 1 : N
- **DB 모델 정답** : Reservation 입장에서 `@ManyToOne Seat` (= Seat 입장에선 1 : N)

> "활성 예약은 1 : 1" 이라는 비즈니스 규칙은 **부분 인덱스(partial unique index)** 또는 애플리케이션 + 락으로 보장한다. 매핑은 어디까지나 1 : N.

**왜 양방향이 아닌가?**

- "이 좌석의 활성 예약" 이 필요하면 `reservationRepo.findBySeat_IdAndStatus(seatId, HOLD)` 로 명시적 쿼리
- 양방향으로 두면 `seat.getReservations()` 가 평생 누적되는 컬렉션이 되어 관리가 어렵다

### 5.6 Payment — Reservation 과 1 : 1 단방향

```java
package com.seat.backend.payment;

import com.seat.backend.reservation.Reservation;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Payment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Reservation ↔ Payment = 1 : 1 (단방향, FK + UNIQUE 는 Payment 가 보유) */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id", nullable = false, unique = true)
    private Reservation reservation;

    @Column(nullable = false)
    private Integer amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = PaymentStatus.REQUESTED;
    }

    public void success() {
        this.status = PaymentStatus.SUCCESS;
        this.paidAt = LocalDateTime.now();
    }

    public void fail() {
        this.status = PaymentStatus.FAILED;
    }
}
```

```java
package com.seat.backend.payment;

public enum PaymentStatus {
    REQUESTED, SUCCESS, FAILED
}
```

**설계 이유**

- 한 예약당 결제는 1 건 → `1 : 1` + `UNIQUE` 제약
- **단방향 (Reservation 에 `Payment` 필드 안 둠)** — 이유 :
  - Reservation 을 조회하는 모든 화면이 결제 정보를 필요로 하지 않음 (목록, 만료 처리에는 결제가 필요 없음)
  - **양방향 1 : 1 은 owner / non-owner 의 LAZY 동작이 까다로움** (특히 non-owner side 는 LAZY 가 잘 안 먹는 경우가 많다 — Hibernate 가 null 인지 확인하려고 무조건 SELECT)
  - 결제가 필요하면 `paymentRepo.findByReservation_Id(resvId)` 로 명시 조회

**`@JoinColumn(unique = true)`**

- DB 레벨에서 한 예약당 결제 1 건 강제. 애플리케이션 검증 + DB 제약 = 이중 안전망

### 5.7 한눈 정리

| 엔티티 | 보유 연관관계 | 매핑 | 방향 |
|---|---|---|---|
| User | (없음) | — | — |
| Event | (없음) | — | — |
| Seat | `Event event` | `@ManyToOne` LAZY | 단방향 |
| Reservation | `User user`, `Seat seat` | `@ManyToOne` LAZY × 2 | 단방향 |
| Payment | `Reservation reservation` | `@OneToOne` LAZY | 단방향 |

`@OneToMany` 컬렉션은 **단 한 곳에도 안 쓴다**. 이게 신입 수준에서 가장 안전하다.

## 6. 리포지토리 인터페이스

연관관계 도입에 따라 메서드 이름이 살짝 바뀐다 — Spring Data 에서 **중첩 프로퍼티는 `_` 로 표기** (`Reservation.seat.id` → `findBy...Seat_Id...`).

`backend/src/main/java/com/seat/backend/user/UserRepository.java`

```java
package com.seat.backend.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
}
```

`backend/src/main/java/com/seat/backend/event/EventRepository.java`

```java
package com.seat.backend.event;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long> {
    List<Event> findByStartAtAfterOrderByStartAtAsc(LocalDateTime now);
}
```

`backend/src/main/java/com/seat/backend/event/SeatRepository.java`

```java
package com.seat.backend.event;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {
    /** 중첩 프로퍼티는 언더스코어로 — Seat.event.id 를 의미 */
    List<Seat> findByEvent_IdOrderBySeatNoAsc(Long eventId);
}
```

`backend/src/main/java/com/seat/backend/reservation/ReservationRepository.java`

```java
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
```

`backend/src/main/java/com/seat/backend/payment/PaymentRepository.java`

```java
package com.seat.backend.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByReservation_Id(Long reservationId);
    boolean existsByReservation_Id(Long reservationId);
}
```

## 7. N+1 문제와 해결

연관관계의 가장 큰 함정. 이걸 모르면 운영 환경에서 DB 가 죽는다.

### 7.1 발생 원리

```java
// 컨트롤러에서 호출되는 가상의 서비스
@Transactional(readOnly = true)
public List<MyReservationDto> myReservations(Long userId) {
    List<Reservation> all = resvRepo.findByUser_Id(userId);   // SQL 1번
    return all.stream()
            .map(r -> new MyReservationDto(
                r.getId(),
                r.getSeat().getSeatNo(),                       // ← LAZY 로딩 N번
                r.getSeat().getEvent().getTitle()              // ← LAZY 로딩 또 N번
            ))
            .toList();
}
```

예약이 100건이면 :

- `resvRepo.findByUser_Id` : 1번
- `r.getSeat()` LAZY 초기화 : 100번
- `r.getSeat().getEvent()` LAZY 초기화 : 100번
- **총 201 쿼리** 🔥

이게 N+1. 단일 사용자 테스트에선 빨라 보이다가, 부하 시 DB 풀이 마르고 응답이 폭주한다.

### 7.2 해결 (1) Fetch Join

명시적 JPQL 로 한 방에 가져온다.

```java
@Query("""
    select r from Reservation r
      join fetch r.seat s
      join fetch s.event
     where r.user.id = :userId
""")
List<Reservation> findWithSeatAndEventByUserId(@Param("userId") Long userId);
```

→ **단 1번의 SQL** 로 Reservation + Seat + Event 까지 모두 로딩.

### 7.3 해결 (2) `@EntityGraph`

JPQL 안 쓰고 어노테이션만으로.

```java
@EntityGraph(attributePaths = {"seat", "seat.event"})
List<Reservation> findByUser_Id(Long userId);
```

→ Spring Data 가 알아서 fetch join 을 끼워넣는다. 메서드 이름은 그대로 유지.

위의 `ReservationRepository.findAllByStatusAndExpiresAtBefore` 가 바로 이 방식 — 만료 스케줄러가 좌석을 매번 LAZY 로 가져오는 N+1 을 막기 위함.

### 7.4 해결 (3) DTO 직접 조회 (**가장 강력**)

화면 전용이라면 엔티티를 안 가져오고 바로 DTO 로.

```java
public record ReservationListView(Long id, String seatNo, String eventTitle, ReservationStatus status) {}
```

```java
@Query("""
    select new com.seat.backend.reservation.dto.ReservationListView(
        r.id, s.seatNo, e.title, r.status
    )
    from Reservation r
      join r.seat s
      join s.event e
    where r.user.id = :userId
""")
List<ReservationListView> findViewsByUserId(@Param("userId") Long userId);
```

장점 :

- 가장 빠름 (영속성 컨텍스트에 엔티티를 안 올림)
- 필요한 컬럼만 SELECT
- 화면 변경에 엔티티가 흔들리지 않음

단점 :

- 도메인 로직(`isExpired()` 등) 사용 못 함
- 화면별로 DTO 가 늘어남

### 7.5 어떤 걸 언제

| 상황 | 추천 |
|---|---|
| 트랜잭션 안에서 도메인 로직도 같이 써야 함 | **fetch join** 또는 `@EntityGraph` |
| 메서드 이름으로 의도가 잘 표현됨 | **`@EntityGraph`** |
| 단순 화면 / 통계 / 카운트 | **DTO 조회** |
| 반복적으로 호출되는 헤드 리스트 | **DTO 조회** (캐시 가능성 ↑) |

> **규칙** : 새 화면 만들 때 첫 줄에 "여기 N+1 안 나나?" 를 체크하는 습관. 로컬에선 안 보이고 운영에서만 터진다.

## 8. 실무 설계 5계명

1. **연관관계는 필요한 최소만 추가한다.** "있으면 좋겠지" 로 추가하면 반드시 N+1 또는 LAZY 함정으로 돌아온다.
2. **기본은 단방향이다.** 부모 → 자식 컬렉션은 99% 의 경우 필요 없다.
3. **양방향은 양쪽 모두 탐색이 정말 필요할 때만.** 그리고 양쪽 sync 책임을 메서드(`addChild`, `removeChild`) 로 캡슐화한다.
4. **`@OneToMany` 컬렉션은 신중하게.** 페이징 안 됨, LAZY 폭탄 위험, cascade 함정. 차라리 자식 리포지토리에 명시 쿼리.
5. **모두 LAZY + 명시적 fetch join.** 안전망과 성능을 둘 다 잡는 유일한 길.

## 9. 코드 설명

- **`@Version Long version`** : JPA 가 UPDATE 마다 `WHERE version = ?` 을 자동으로 끼워넣고 영향받은 행이 0이면 `OptimisticLockingFailureException` 을 던진다. **Step 10 의 핵심 인프라.**
- **도메인 행위(`hold()`, `confirm()`, `release()`)** : 상태 전이 로직을 엔티티 안에 둔다. 서비스에서 `seat.setStatus(...)` 같은 것을 직접 못하게 막아 무결성을 보장.
- **`@NoArgsConstructor(access = PROTECTED)`** : JPA 는 기본 생성자가 필요하지만, 외부에서 막 호출하면 안 되니 protected 로 닫는다.
- **모든 `@ManyToOne` / `@OneToOne` 에 명시적 LAZY** : 디폴트가 EAGER 라는 함정을 봉쇄.

## 10. 핵심 개념 요약

- 도메인 5개 중 4개의 관계, 모두 **`@ManyToOne` 또는 `@OneToOne`** 단방향 + LAZY
- `@OneToMany` 컬렉션은 **0개** — 명시 쿼리로 대체
- `@Version` 만 붙여 두면 낙관적 락이 자동 동작
- 도메인 행위(`hold()`, `confirm()`, `release()`)는 엔티티 안에
- N+1 은 **fetch join / `@EntityGraph` / DTO 조회** 셋 중 하나로 항상 막는다

## 11. 면접 예상 질문

1. **단방향 vs 양방향, 언제 어떤 걸?**
   → 기본은 단방향. 양방향은 양쪽에서 모두 탐색이 정말 필요할 때만, 그리고 sync 메서드까지 책임지고 만들 자신이 있을 때.

2. **`@ManyToOne(LAZY)` 인데도 N+1 이 나는 이유?**
   → 부모 엔티티 N개를 조회한 뒤 각각의 LAZY 관계에 접근하면 행마다 SELECT 가 추가로 나간다. LAZY 는 "1번 더 줄 서서 사올 수 있다" 는 뜻이지 "0번" 이 아님.

3. **`@OneToMany` 양방향 + cascade=ALL 의 함정?**
   → 부모 저장만 했는데 자식까지 같이 INSERT 되어 트랜잭션 비대화, 부모 삭제 시 의도치 않은 자식 삭제, 무한 직렬화 위험. 명시적 save 를 선호하는 팀이 많다.

4. **FK 컬럼만 두는 게 더 좋을 때는?**
   → ID 만 알면 충분한 단순 참조 (예: 외부 사용자 식별자), 대용량 배치, 통계 / 리포트, JPA 영속성 비용을 피하고 싶은 핫스팟.

5. **`@OneToOne` 양방향이 까다로운 이유?**
   → 외래키 보유 측(owner) 의 LAZY 는 잘 동작하지만, **반대편(non-owner)은 LAZY 가 안 먹는 경우가 많다** (Hibernate 가 null 인지 확인하려고 무조건 SELECT). 그래서 `@OneToOne` 은 거의 항상 단방향으로 한 쪽에만.

6. **`@Version` 컬럼은 INT 가 좋나 LONG 이 좋나?**
   → 차이는 거의 없지만 long 이 안전 — INT_MAX 도달 가능성을 0으로 줄임. `@Version BIGINT` 가 가장 무난.

---

# Step 5 — JWT 인증

## 1. 목표

회원가입 / 로그인 → JWT Access Token 발급 → 이후 API 는 `Authorization: Bearer ...` 로 인증.

## 2. JWT 프로바이더 — `auth/JwtTokenProvider.java`

```java
package com.seat.backend.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final long expirationMs;

    public JwtTokenProvider(@Value("${app.jwt.secret}") String secret,
                            @Value("${app.jwt.access-expiration-minutes}") long minutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = minutes * 60_000;
    }

    public String createToken(Long userId, String role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
```

## 3. 인증 필터 — `auth/JwtAuthenticationFilter.java`

```java
package com.seat.backend.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwt;

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims c = jwt.parse(token);
                Long userId = Long.valueOf(c.getSubject());
                String role = c.get("role", String.class);
                var auth = new UsernamePasswordAuthenticationToken(
                        userId, null, List.of(new SimpleGrantedAuthority(role)));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException e) {
                log.warn("JWT 검증 실패: {}", e.getMessage());
            }
        }
        chain.doFilter(req, res);
    }
}
```

## 4. SecurityConfig — `config/SecurityConfig.java`

```java
package com.seat.backend.config;

import com.seat.backend.auth.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/", "/login", "/signup", "/css/**", "/js/**",
                    "/api/auth/**", "/api/events/**"           // 공연 조회는 비로그인 허용
                ).permitAll()
                .requestMatchers("/api/reservations/**", "/api/payments/**").authenticated()
                .anyRequest().permitAll()
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

## 5. 회원가입 / 로그인 DTO

```java
package com.seat.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class SignUpRequest {
    @Email @NotBlank private String email;
    @NotBlank @Size(min = 6, max = 30) private String password;
    @NotBlank @Size(max = 50) private String nickname;
}
```

```java
package com.seat.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class LoginRequest {
    @Email @NotBlank private String email;
    @NotBlank private String password;
}
```

```java
package com.seat.backend.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class TokenResponse {
    private String accessToken;
}
```

## 6. AuthService

```java
package com.seat.backend.auth;

import com.seat.backend.auth.dto.LoginRequest;
import com.seat.backend.auth.dto.SignUpRequest;
import com.seat.backend.auth.dto.TokenResponse;
import com.seat.backend.common.BusinessException;
import com.seat.backend.common.ErrorCode;
import com.seat.backend.user.User;
import com.seat.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepo;
    private final PasswordEncoder encoder;
    private final JwtTokenProvider jwt;

    @Transactional
    public Long signUp(SignUpRequest req) {
        if (userRepo.existsByEmail(req.getEmail())) {
            throw new BusinessException(ErrorCode.EMAIL_DUPLICATED);
        }
        User saved = userRepo.save(User.builder()
                .email(req.getEmail())
                .password(encoder.encode(req.getPassword()))
                .nickname(req.getNickname())
                .role("ROLE_USER")
                .build());
        return saved.getId();
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest req) {
        User user = userRepo.findByEmail(req.getEmail())
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_CREDENTIALS));
        if (!encoder.matches(req.getPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.BAD_CREDENTIALS);
        }
        return new TokenResponse(jwt.createToken(user.getId(), user.getRole()));
    }
}
```

## 7. AuthController

```java
package com.seat.backend.auth;

import com.seat.backend.auth.dto.LoginRequest;
import com.seat.backend.auth.dto.SignUpRequest;
import com.seat.backend.auth.dto.TokenResponse;
import com.seat.backend.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/signup")
    public ApiResponse<Long> signUp(@RequestBody @Valid SignUpRequest req) {
        return ApiResponse.ok(authService.signUp(req));
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@RequestBody @Valid LoginRequest req) {
        return ApiResponse.ok(authService.login(req));
    }
}
```

## 8. 인증 유틸 — `auth/AuthUser.java`

컨트롤러에서 현재 로그인 사용자 ID 를 한 줄로 꺼내기 위한 유틸.

```java
package com.seat.backend.auth;

import com.seat.backend.common.BusinessException;
import com.seat.backend.common.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class AuthUser {
    public static Long currentUserId() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || a.getPrincipal() == null || !(a.getPrincipal() instanceof Long)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return (Long) a.getPrincipal();
    }
}
```

## 9. 실행 결과

```bash
# 회원가입
curl -X POST http://localhost:8080/api/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"email":"a@a.com","password":"123456","nickname":"민수"}'

# 로그인
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"a@a.com","password":"123456"}'
```

응답 :

```json
{ "success": true, "data": { "accessToken": "eyJhbGciOi..." } }
```

## 10. 핵심 개념 요약

- JWT 는 무상태 — 서버는 검증만, 세션 저장 안 함.
- `BCrypt` 는 단방향 해시 + salt — 같은 비밀번호도 매번 다른 해시.
- `STATELESS` 세션 정책으로 서버 확장이 자유로움.

## 11. 면접 예상 질문

1. **JWT 의 단점은?**
   → 토큰을 폐기하기 어렵다(서버가 상태를 안 들고 있으므로). 해결책은 짧은 만료 + Refresh, 또는 블랙리스트 캐시.
2. **CSRF 를 disable 한 이유는?**
   → JWT 는 헤더로 보내고 쿠키 인증을 안 쓰므로 CSRF 공격 표면이 없다. 쿠키 인증을 함께 쓴다면 다시 켜야 한다.

---

# Step 6 — 공연/좌석 API

## 1. 목표

공연 등록 / 다가오는 공연 조회 / 좌석 목록 조회 API 를 만든다. 예약 API 는 다음 단계에서.

## 2. EventService

`backend/src/main/java/com/seat/backend/event/EventService.java`

```java
package com.seat.backend.event;

import com.seat.backend.common.BusinessException;
import com.seat.backend.common.ErrorCode;
import com.seat.backend.event.dto.EventResponse;
import com.seat.backend.event.dto.SeatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepo;
    private final SeatRepository seatRepo;

    @Transactional(readOnly = true)
    public List<EventResponse> upcoming() {
        return eventRepo.findByStartAtAfterOrderByStartAtAsc(LocalDateTime.now())
                .stream().map(EventResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<SeatResponse> seatsOf(Long eventId) {
        if (!eventRepo.existsById(eventId)) {
            throw new BusinessException(ErrorCode.EVENT_NOT_FOUND);
        }
        return seatRepo.findByEvent_IdOrderBySeatNoAsc(eventId)
                .stream().map(SeatResponse::from).toList();
    }
}
```

## 3. DTO — `event/dto/EventResponse.java`

```java
package com.seat.backend.event.dto;

import com.seat.backend.event.Event;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class EventResponse {
    private Long id;
    private String title;
    private LocalDateTime startAt;

    public static EventResponse from(Event e) {
        return new EventResponse(e.getId(), e.getTitle(), e.getStartAt());
    }
}
```

```java
package com.seat.backend.event.dto;

import com.seat.backend.event.Seat;
import com.seat.backend.event.SeatStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SeatResponse {
    private Long id;
    private String seatNo;
    private SeatStatus status;

    public static SeatResponse from(Seat s) {
        return new SeatResponse(s.getId(), s.getSeatNo(), s.getStatus());
    }
}
```

## 4. EventController

```java
package com.seat.backend.event;

import com.seat.backend.common.ApiResponse;
import com.seat.backend.event.dto.EventResponse;
import com.seat.backend.event.dto.SeatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @GetMapping
    public ApiResponse<List<EventResponse>> upcoming() {
        return ApiResponse.ok(eventService.upcoming());
    }

    @GetMapping("/{eventId}/seats")
    public ApiResponse<List<SeatResponse>> seats(@PathVariable Long eventId) {
        return ApiResponse.ok(eventService.seatsOf(eventId));
    }
}
```

## 5. API 요약

| Method | URL | 인증 | 설명 |
|---|---|---|---|
| `GET`  | `/api/events` | X | 다가오는 공연 목록 |
| `GET`  | `/api/events/{id}/seats` | X | 좌석 목록 (status 포함) |

응답 예 (`/api/events/1/seats`) :

```json
{
  "success": true,
  "data": [
    { "id": 1, "seatNo": "A1", "status": "AVAILABLE" },
    { "id": 2, "seatNo": "A2", "status": "AVAILABLE" }, ...
  ]
}
```

## 6. 핵심 개념 요약

- **읽기 전용 트랜잭션**(`readOnly=true`) → Hibernate 가 dirty checking 을 건너뛰어 약간 빠름.
- DTO 변환은 **엔티티의 정적 팩토리(`from(...)`)** 로 — 컨트롤러가 엔티티를 직접 노출하지 않게.

## 7. 면접 예상 질문

1. **엔티티를 그대로 응답에 노출하면 뭐가 위험한가?**
   → 비밀번호 같은 민감 필드 누출, 양방향 연관관계로 인한 무한 직렬화, 내부 구조 변경이 곧 API 변경이 됨. DTO 분리가 정석.

---

# Step 7 — 최소 UI

## 1. 목표

Thymeleaf 로 4개 화면(로그인/회원가입/공연 목록/좌석 선택)만 만든다. **목적은 시연**이지 UI 개발이 아니다.

## 2. ViewController

`backend/src/main/java/com/seat/backend/web/ViewController.java`

```java
package com.seat.backend.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class ViewController {

    @GetMapping("/")
    public String home() { return "index"; }

    @GetMapping("/login")
    public String login() { return "login"; }

    @GetMapping("/signup")
    public String signup() { return "signup"; }

    @GetMapping("/events/{eventId}/seats")
    public String seatPage(@PathVariable Long eventId) {
        return "seats";   // /api/events/{id}/seats 는 데이터, /events/{id}/seats 는 화면
    }
}
```

## 3. `templates/index.html` — 공연 목록

`backend/src/main/resources/templates/index.html`

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8"><title>공연 목록</title>
</head>
<body>
<h1>다가오는 공연</h1>
<a href="/login">로그인</a> | <a href="/signup">회원가입</a>
<hr>
<ul id="events"></ul>
<script>
fetch('/api/events').then(r => r.json()).then(res => {
    const ul = document.getElementById('events');
    res.data.forEach(ev => {
        const li = document.createElement('li');
        li.innerHTML = `<a href="/events/${ev.id}/seats">${ev.title} (${ev.startAt})</a>`;
        ul.appendChild(li);
    });
});
</script>
</body>
</html>
```

## 4. `templates/signup.html` / `templates/login.html`

```html
<!-- signup.html -->
<!DOCTYPE html><html><head><meta charset="UTF-8"><title>회원가입</title></head><body>
<h1>회원가입</h1>
<form id="f">
    <input name="email" placeholder="이메일"><br>
    <input name="password" type="password" placeholder="비밀번호 6자 이상"><br>
    <input name="nickname" placeholder="닉네임"><br>
    <button>가입</button>
</form>
<script>
document.getElementById('f').onsubmit = async e => {
    e.preventDefault();
    const fd = Object.fromEntries(new FormData(e.target));
    const r = await fetch('/api/auth/signup', {
        method:'POST', headers:{'Content-Type':'application/json'},
        body: JSON.stringify(fd)
    });
    const j = await r.json();
    alert(j.success ? '가입 완료. 로그인하세요.' : j.message);
    if (j.success) location.href = '/login';
};
</script></body></html>
```

```html
<!-- login.html -->
<!DOCTYPE html><html><head><meta charset="UTF-8"><title>로그인</title></head><body>
<h1>로그인</h1>
<form id="f">
    <input name="email" placeholder="이메일"><br>
    <input name="password" type="password" placeholder="비밀번호"><br>
    <button>로그인</button>
</form>
<script>
document.getElementById('f').onsubmit = async e => {
    e.preventDefault();
    const fd = Object.fromEntries(new FormData(e.target));
    const r = await fetch('/api/auth/login', {
        method:'POST', headers:{'Content-Type':'application/json'},
        body: JSON.stringify(fd)
    });
    const j = await r.json();
    if (!j.success) return alert(j.message);
    localStorage.setItem('token', j.data.accessToken);
    location.href = '/';
};
</script></body></html>
```

## 5. `templates/seats.html` — 핵심 화면

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8"><title>좌석 선택</title>
    <style>
        .seat { display:inline-block; width:60px; height:60px; margin:6px;
                line-height:60px; text-align:center; border:1px solid #999;
                cursor:pointer; user-select:none; }
        .AVAILABLE { background:#cfc; }
        .HOLD      { background:#fc9; cursor:not-allowed; }
        .RESERVED  { background:#999; color:#fff; cursor:not-allowed; }
        .CANCELLED { background:#fff; color:#bbb; }
    </style>
</head>
<body>
<h1>좌석 선택</h1>
<p><a href="/">← 목록</a></p>
<div id="grid"></div>
<hr>
<button id="reserveBtn" disabled>예약 요청</button>
<button id="payBtn" disabled>결제하기</button>
<p id="msg"></p>

<script th:inline="javascript">
const eventId = [[${#httpServletRequest.requestURI.split('/')[2]}]];
let selectedSeatId = null;
let currentReservationId = null;
const token = () => localStorage.getItem('token');

async function load() {
    const r = await fetch('/api/events/' + eventId + '/seats');
    const j = await r.json();
    const g = document.getElementById('grid');
    g.innerHTML = '';
    j.data.forEach(s => {
        const d = document.createElement('div');
        d.className = 'seat ' + s.status;
        d.textContent = s.seatNo;
        d.dataset.id = s.id;
        d.dataset.status = s.status;
        if (s.status === 'AVAILABLE') {
            d.onclick = () => {
                selectedSeatId = s.id;
                document.getElementById('reserveBtn').disabled = false;
                document.querySelectorAll('.seat').forEach(x => x.style.outline = '');
                d.style.outline = '3px solid red';
                document.getElementById('msg').textContent = s.seatNo + ' 선택됨';
            };
        }
        g.appendChild(d);
    });
}

document.getElementById('reserveBtn').onclick = async () => {
    if (!token()) { alert('로그인이 필요합니다.'); return location.href='/login'; }
    const r = await fetch('/api/reservations', {
        method:'POST',
        headers:{'Content-Type':'application/json','Authorization':'Bearer '+token()},
        body: JSON.stringify({ seatId: selectedSeatId })
    });
    const j = await r.json();
    if (!j.success) { alert(j.message); return load(); }
    currentReservationId = j.data.reservationId;
    document.getElementById('payBtn').disabled = false;
    document.getElementById('msg').textContent = '예약 성공! 5분 안에 결제하세요. (예약 ID '+currentReservationId+')';
    load();
};

document.getElementById('payBtn').onclick = async () => {
    const r = await fetch('/api/payments', {
        method:'POST',
        headers:{'Content-Type':'application/json','Authorization':'Bearer '+token()},
        body: JSON.stringify({ reservationId: currentReservationId, amount: 50000 })
    });
    const j = await r.json();
    alert(j.success ? '결제 완료!' : '결제 실패: '+j.message);
    document.getElementById('payBtn').disabled = true;
    load();
};

load();
setInterval(load, 3000);   // 3초마다 좌석 상태 갱신
</script>
</body>
</html>
```

## 6. 요청 흐름 설명

```
[브라우저]
   1. /events/1/seats 접속 → 정적 화면 로드
   2. JS 가 GET /api/events/1/seats 호출 → 좌석 그리드 렌더링
   3. AVAILABLE 좌석 클릭 → selectedSeatId 저장
   4. "예약 요청" 클릭 → POST /api/reservations { seatId } (with JWT)
        → 성공: HOLD 로 바뀜, 결제 버튼 활성화
        → 실패(409): "이미 선점된 좌석" 표시 후 새로고침
   5. "결제하기" 클릭 → POST /api/payments { reservationId, amount }
        → 성공: RESERVED 로 확정
   6. setInterval(load, 3000) → 3초마다 다른 사용자의 변경 반영
```

## 7. 핵심 개념 요약

- 시연용 UI 는 디자인이 아니라 **상태 변화 확인**이 목표.
- `localStorage` 에 JWT 저장 → fetch 헤더에 자동 첨부.
- 좌석 색상으로 상태 머신을 시각적으로 보여준다.

## 8. 면접 예상 질문

1. **`localStorage` 에 토큰을 저장하는 게 안전한가?**
   → XSS 에 취약. 운영급에서는 HttpOnly Cookie + CSRF 토큰 조합이 안전. 이 실습에선 단순화를 위해 사용.

---

# Step 8 — 예약 기능 기본 구현

## 1. 목표

**락 없는 버전** 의 예약 API 를 먼저 만들어 동시성 문제(이중 예약)를 직접 재현한다. 이게 있어야 락의 필요성이 체감된다.

## 2. ReservationService — 락 없는 버전

`backend/src/main/java/com/seat/backend/reservation/ReservationService.java`

```java
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
}
```

## 3. DTO

```java
package com.seat.backend.reservation.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Getter
public class ReservationRequest {
    @NotNull private Long seatId;
}
```

```java
package com.seat.backend.reservation.dto;

import com.seat.backend.reservation.Reservation;
import com.seat.backend.reservation.ReservationStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class ReservationResponse {
    private Long reservationId;
    private Long seatId;
    private ReservationStatus status;
    private LocalDateTime expiresAt;

    public static ReservationResponse from(Reservation r) {
        // r.getSeat() 는 LAZY 프록시 — getId() 만 호출하면 추가 SQL 없음 (Hibernate 최적화)
        return new ReservationResponse(r.getId(), r.getSeat().getId(), r.getStatus(), r.getExpiresAt());
    }
}
```

## 4. ReservationController

```java
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

    @PostMapping
    public ApiResponse<ReservationResponse> reserve(@RequestBody @Valid ReservationRequest req) {
        Long userId = AuthUser.currentUserId();
        return ApiResponse.ok(reservationService.reserveNoLock(userId, req.getSeatId()));
    }
}
```

## 5. 동시성 문제 재현

`backend/src/test/java/com/seat/backend/reservation/ReservationConcurrencyTest.java`

```java
package com.seat.backend.reservation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ReservationConcurrencyTest {

    @Autowired ReservationService reservationService;
    @Autowired ReservationRepository reservationRepo;

    @Test
    void 같은_좌석에_100명이_동시_예약하면_1명만_성공해야_한다() throws InterruptedException {
        Long seatId = 1L;
        int threads = 100;

        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail    = new AtomicInteger();

        for (int i = 1; i <= threads; i++) {
            final long userId = i;
            pool.submit(() -> {
                try {
                    reservationService.reserveNoLock(userId, seatId);
                    success.incrementAndGet();
                } catch (Exception e) {
                    fail.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();

        System.out.printf("success=%d, fail=%d%n", success.get(), fail.get());
        // 락 없는 버전 → success > 1 인 케이스가 발생 (테스트 깨짐) → 다음 단계에서 수정
        assertThat(success.get()).isEqualTo(1);
    }
}
```

이 테스트는 **현재 단계에서는 깨지는 것이 정상**이다 — 락이 없으니 여러 트랜잭션이 동시에 `AVAILABLE` 을 읽고 둘 이상 INSERT 한다. 다음 단계부터 락을 도입해 통과시킨다.

## 6. 코드 설명

- `reserveNoLock` 의 `findById` ↔ `hold()` 사이가 **race window**.
- 트랜잭션 격리 수준이 기본(`READ_COMMITTED`)이면 두 트랜잭션이 같은 행을 읽고 둘 다 UPDATE 에 성공한다.
- 단지 `@Transactional` 만 붙이면 되는 게 아니라 **무엇을 락할지** 를 명시해야 한다.

## 7. 핵심 개념 요약

- 락 없는 코드는 단일 사용자 시나리오에서는 멀쩡해 보이지만 **부하 시 무너진다**.
- 동시성 버그는 잘 안 보여서 더 무섭다 — 자동화 테스트로 재현하는 습관이 중요.

## 8. 면접 예상 질문

1. **격리 수준을 SERIALIZABLE 로 올리면 락 없이 해결되지 않나?**
   → 이론상 가능하나 모든 SELECT 가 직렬화돼 throughput 이 폭락한다. 핫스팟에만 락을 거는 편이 비용 대비 효과적.

---

# Step 9 — 동시성 처리 (1) 비관적 락

## 1. 개념

**비관적 락(Pessimistic Lock)** 은 "충돌이 자주 일어날 것이다" 라는 가정으로 **읽는 순간 행에 X-Lock 을 걸어** 다른 트랜잭션은 commit/rollback 까지 대기시키는 방식. MySQL InnoDB 에서는 `SELECT ... FOR UPDATE` 가 그것이다.

```
T1: SELECT seat#10 FOR UPDATE  →  X-lock 획득
T2: SELECT seat#10 FOR UPDATE  →  대기...
T1: UPDATE status=HOLD; COMMIT  →  락 해제
T2: 깨어남 → SELECT 결과 status=HOLD 확인 → 예외 던지고 종료
```

| 장점 | 단점 |
|---|---|
| 가장 직관적 — 코드 보기 좋음 | 락 보유 시간만큼 다른 요청이 대기 (throughput 감소) |
| 충돌이 잦은 핫스팟에서 가장 안정 | DB 락이라 멀티 인스턴스에서도 안전, 단 락 풀린 후 깨어나는 비용 큼 |
| 추가 컬럼/인프라 불필요 | 외부 API 호출 같은 긴 작업을 락 안에 두면 큰일 |

## 2. SeatRepository 에 락 메서드 추가

```java
package com.seat.backend.event;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByEvent_IdOrderBySeatNoAsc(Long eventId);

    /** 비관적 락 — SELECT ... FOR UPDATE */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.id = :id")
    Optional<Seat> findByIdForUpdate(@Param("id") Long id);
}
```

## 3. ReservationService — 비관적 락 버전

`reserveWithPessimistic` 메서드를 추가.

```java
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
```

## 4. 컨트롤러를 비관적 락 버전으로 교체

```java
@PostMapping
public ApiResponse<ReservationResponse> reserve(@RequestBody @Valid ReservationRequest req) {
    Long userId = AuthUser.currentUserId();
    return ApiResponse.ok(reservationService.reserveWithPessimistic(userId, req.getSeatId()));
}
```

## 5. 실행 결과

Step 8 의 테스트(`reserveNoLock` → `reserveWithPessimistic` 으로 교체) 를 다시 실행.

```
success=1, fail=99
```

100명 중 단 1명만 성공. 나머지는 `SEAT_NOT_AVAILABLE` 예외로 실패.

## 6. 락 타임아웃 설정 (선택)

대기가 너무 길면 사용자 경험이 망가진다. JPA hint 로 타임아웃을 줄 수 있다.

```java
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.QueryHints;

@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})  // 3초
@Query("select s from Seat s where s.id = :id")
Optional<Seat> findByIdForUpdate(@Param("id") Long id);
```

타임아웃 초과 시 `LockTimeoutException` 발생 → `LOCK_FAILED` 응답으로 매핑하면 사용자에게 "잠시 후 다시 시도" 메시지를 보낼 수 있다.

## 7. 핵심 개념 요약

- `@Lock(PESSIMISTIC_WRITE)` 한 줄이면 끝 — 가장 단순.
- **트랜잭션 안에서만 의미 있음** — `@Transactional` 없이 호출하면 락이 즉시 풀려서 의미 없음.
- 락 보유 시간 = 트랜잭션 시간이므로 **외부 API 호출은 트랜잭션 밖에서**.

## 8. 면접 예상 질문

1. **`PESSIMISTIC_WRITE` 와 `PESSIMISTIC_READ` 의 차이는?**
   → 전자는 X-Lock(쓰기/읽기 모두 차단), 후자는 S-Lock(다른 읽기는 허용, 쓰기만 차단). 좌석 예약처럼 즉시 쓸 거면 WRITE 가 맞다.
2. **데드락이 날 수 있나?**
   → 여러 행을 다른 순서로 잠그면 가능. 좌석 1개씩 처리하는 이 시나리오에선 덜 위험하지만, 다중 좌석 예약을 지원하려면 **좌석 ID 오름차순 정렬 후 락** 같은 규칙으로 데드락을 회피해야 한다.

---

# Step 10 — 동시성 처리 (2) 낙관적 락

## 1. 개념

**낙관적 락(Optimistic Lock)** 은 "충돌은 드물 것이다" 라는 가정으로 **락을 안 걸고 진행** 하다가, UPDATE 시점에 `version` 컬럼이 그 사이 변경됐는지 검사해 충돌을 감지하는 방식.

```
T1: SELECT seat#10  → version=0
T2: SELECT seat#10  → version=0
T1: UPDATE ... SET status=HOLD, version=1 WHERE id=10 AND version=0   → 1 row 영향, 성공
T2: UPDATE ... SET status=HOLD, version=1 WHERE id=10 AND version=0   → 0 row 영향
                                                                       → OptimisticLockingFailureException
```

| 장점 | 단점 |
|---|---|
| 락이 없어 **읽기 throughput 최고** | 충돌 시 트랜잭션 전체 롤백 → 재시도 필요 |
| DB 가 무엇이든 동작 | 충돌이 잦으면 재시도 폭주로 오히려 느려짐 |
| 외부 API 호출 같은 긴 작업과 잘 어울림 | 비즈니스 로직이 멱등해야 재시도 안전 |

좌석 예약은 **핫 좌석에 충돌이 매우 잦기 때문에** 낙관적 락만 쓰면 재시도가 폭증한다. 그래도 학습상 반드시 한 번은 짜본다.

## 2. Seat 엔티티의 `@Version` 활용

Step 4 에서 이미 `@Version Long version` 을 추가해 두었다. 별도 코드 변경 없이 JPA 가 자동으로 `WHERE version = ?` 를 끼워준다.

## 3. ReservationService — 낙관적 락 + 재시도

```java
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@Transactional
public ReservationResponse reserveWithOptimisticInternal(Long userId, Long seatId) {
    Seat seat = seatRepo.findById(seatId)
            .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND));

    if (seat.getStatus() != SeatStatus.AVAILABLE) {
        throw new BusinessException(ErrorCode.SEAT_NOT_AVAILABLE);
    }

    seat.hold();
    // commit 시점에 UPDATE seats SET status='HOLD', version=version+1 WHERE id=? AND version=?
    // → 그 사이 다른 트랜잭션이 version 을 올렸다면 0 row 영향 → OptimisticLockingFailureException

    User userRef = userRepo.getReferenceById(userId);
    Reservation r = resvRepo.save(Reservation.builder()
            .user(userRef).seat(seat)
            .status(ReservationStatus.HOLD)
            .expiresAt(LocalDateTime.now().plusSeconds(holdSeconds))
            .build());

    return ReservationResponse.from(r);
}

/** 낙관적 락 + 짧은 재시도 (최대 3회) — 트랜잭션 밖 래퍼 */
public ReservationResponse reserveWithOptimistic(Long userId, Long seatId) {
    int maxRetry = 3;
    for (int i = 0; i < maxRetry; i++) {
        try {
            return reserveWithOptimisticInternal(userId, seatId);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockingFailureException e) {
            // 다음 시도 전에 좌석이 이미 HOLD/RESERVED 가 됐는지 확인 → 그렇다면 즉시 실패
            Seat fresh = seatRepo.findById(seatId).orElseThrow();
            if (fresh.getStatus() != SeatStatus.AVAILABLE) {
                throw new BusinessException(ErrorCode.SEAT_NOT_AVAILABLE);
            }
            // 아직 AVAILABLE 이면 짧게 대기 후 재시도
            try { Thread.sleep(20L * (i + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
    }
    throw new BusinessException(ErrorCode.LOCK_FAILED);
}
```

## 4. 재시도 메서드와 트랜잭션 경계의 함정

`reserveWithOptimistic` 은 **`@Transactional` 이 없는 외부 메서드**. 안에서 `@Transactional` 이 붙은 `reserveWithOptimisticInternal` 을 호출한다. 이렇게 하지 않으면:

- 한 트랜잭션 안에서 try/catch 로 잡고 재호출 → JPA 영속성 컨텍스트가 여전히 같은 dirty entity 를 들고 있어 동일 충돌이 반복.
- **재시도 = 새 트랜잭션** 이어야 깨끗한 상태로 다시 시도 가능.

> **주의** : 같은 클래스 안에서 `this.reserveWithOptimisticInternal(...)` 을 호출하면 Spring AOP 프록시를 우회해 `@Transactional` 이 안 먹는다. 별도 빈으로 분리하거나, `AopContext.currentProxy()` 를 쓰거나, **이 실습에선 같은 서비스 안에서 호출하되 둘 다 트랜잭션 어노테이션을 명시적으로 관리** 한다. 안전하게 하려면 다음과 같이 분리:

```java
@Service
@RequiredArgsConstructor
public class ReservationOptimisticService {

    private final UserRepository userRepo;
    private final SeatRepository seatRepo;
    private final ReservationRepository resvRepo;

    @Value("${app.reservation.hold-seconds}")
    private long holdSeconds;

    @Transactional
    public ReservationResponse reserveOnce(Long userId, Long seatId) { /* 위의 internal 본문 */ }
}
```

그리고 재시도 래퍼는 `ReservationService` 가 `ReservationOptimisticService` 를 주입받아 호출.

## 5. 실행 결과

```
success=1, fail=99
```

비관적 락과 동일한 결과지만, **DB 락이 없으니 throughput 측면에선 충돌이 적은 케이스에서 더 빠르다**. 다만 좌석 예약처럼 한 행에 100명이 몰리면 99번이 재시도 후 실패하므로 비관적 락보다 비용이 높을 수 있다.

## 6. 핵심 개념 요약

- `@Version` 한 컬럼이면 인프라 끝.
- **재시도 래퍼는 트랜잭션 밖** 에서 — 트랜잭션 내부 catch 로는 의미 없음.
- 충돌 재시도가 무한 루프 되지 않도록 **최대 횟수** 와 **즉시 실패 조건(이미 HOLD/RESERVED)** 을 둔다.

## 7. 면접 예상 질문

1. **낙관적 락 충돌이 잦으면 어떻게 하나?**
   → (1) 비관적 락으로 갈아탄다, (2) 충돌 감소를 위해 작업 단위를 잘게 쪼갠다, (3) 재시도 백오프(지수)를 둔다.
2. **낙관적 락은 멀티 인스턴스에서도 안전한가?**
   → 안전하다. `WHERE version = ?` 검사가 DB 에서 일어나므로 어느 인스턴스에서 시작했든 결과는 동일.

---

# Step 11 — 동시성 처리 (3) Redis 분산 락

## 1. 개념

**분산 락(Distributed Lock)** 은 락 자체를 DB 가 아닌 **외부 저장소(Redis)** 에 두는 방식. Redisson 라이브러리가 사실상 표준이다.

```
T1: redisson.getLock("lock:seat:10").tryLock(2s, 3s)  → 획득
T2: redisson.getLock("lock:seat:10").tryLock(2s, 3s)  → 대기...
T1: 좌석 처리 → unlock()
T2: 깨어남 → 좌석 처리
```

| 장점 | 단점 |
|---|---|
| 락 대상이 DB 행이 아니어도 가능 (예: "결제 API 호출 1초당 1건") | Redis 자체가 SPOF — 클러스터 + sentinel 필요 |
| 트랜잭션 시작 전에 줄을 세울 수 있음 | TTL 만료 함정 (GC 정지 등) |
| 매우 짧은 락 → 높은 throughput 가능 | 추가 인프라 의존 |

## 2. Redisson 설정 — `config/RedissonConfig.java`

```java
package com.seat.backend.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port) {
        Config config = new Config();
        config.useSingleServer()
              .setAddress("redis://" + host + ":" + port)
              .setConnectionMinimumIdleSize(4)
              .setConnectionPoolSize(16);
        return Redisson.create(config);
    }
}
```

## 3. ReservationService — 분산 락 버전

```java
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class ReservationDistributedService {

    private static final long WAIT_SEC  = 2;   // 락 획득 대기
    private static final long LEASE_SEC = 3;   // 락 보유 시간 (TTL)

    private final RedissonClient redisson;
    private final ReservationOptimisticService inner;   // 트랜잭션이 붙은 본 로직

    public ReservationResponse reserve(Long userId, Long seatId) {
        String key = "lock:seat:" + seatId;
        RLock lock = redisson.getLock(key);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(WAIT_SEC, LEASE_SEC, TimeUnit.SECONDS);
            if (!acquired) {
                throw new BusinessException(ErrorCode.LOCK_FAILED);
            }
            // 락 안에서 트랜잭션 시작
            return inner.reserveOnce(userId, seatId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.LOCK_FAILED);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

## 4. 락 ↔ 트랜잭션의 순서 (매우 중요)

```
[ 잘못된 순서 ]
@Transactional 시작 → tryLock → 작업 → unlock → @Transactional 커밋
                                                  ▲
                                                  │ 이 사이에 다른 노드가 락 획득 가능
                                                  │ 그런데 내 트랜잭션은 아직 커밋 전 → 다른 노드가 옛날 데이터를 읽음
```

```
[ 올바른 순서 ]
tryLock → @Transactional 시작 → 작업 → 커밋 → unlock
```

→ **항상 락 획득이 먼저, 트랜잭션이 그 다음**. 위 코드처럼 외부 메서드(`reserve`)에서 락을 잡고, 내부 트랜잭션 메서드(`reserveOnce`)를 호출하는 구조가 정석.

## 5. 컨트롤러를 분산 락 버전으로 교체

```java
@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationDistributedService reservationService;

    @PostMapping
    public ApiResponse<ReservationResponse> reserve(@RequestBody @Valid ReservationRequest req) {
        Long userId = AuthUser.currentUserId();
        return ApiResponse.ok(reservationService.reserve(userId, req.getSeatId()));
    }
}
```

## 6. 세 방식의 비교

| 항목 | 비관적 락 | 낙관적 락 | Redis 분산 락 |
|---|---|---|---|
| 인프라 | DB 만 | DB + version 컬럼 | DB + Redis + Redisson |
| 락 위치 | DB 행 | 없음 (충돌 감지) | Redis 키 |
| 충돌 잦을 때 | ★★★★ 안정 | ★ 재시도 폭주 | ★★★★ 안정 |
| 충돌 드물 때 | ★★ 불필요한 락 | ★★★★ 가장 빠름 | ★★★ 보통 |
| 멀티 인스턴스 | ★★★★ OK | ★★★★ OK | ★★★★ OK |
| 락 안에 외부 API | ✕ (커넥션 점유) | ✕ (재시도 폭증) | △ (TTL 주의) |
| 코드 복잡도 | 가장 단순 | 재시도 로직 필요 | 락/트랜잭션 순서 주의 |

**좌석 예약 같은 핫스팟에서는 비관적 락이 가장 무난** 하다. 분산 락은 **DB 락 + 외부 호출 직렬화** 같은 복합 시나리오에서 빛난다. 이 실습의 운영 모드는 **비관적 락**을 디폴트로 쓴다 (다음 단계의 결제와 잘 맞음).

## 7. 핵심 개념 요약

- 분산 락은 **락 → 트랜잭션 → 커밋 → 언락** 순서 절대 어기지 말기.
- TTL 만료로 락이 풀릴 수 있다 — Redisson 의 watchdog 이 자동 갱신해주지만 외부 API 호출 시간을 락 안에 두지 말 것.
- 매우 짧고 빠른 직렬화에는 강력하지만, **운영 도입은 비관적 락보다 비용이 큼**.

## 8. 면접 예상 질문

1. **Redisson 의 `tryLock(wait, lease, unit)` 의 두 인자 차이는?**
   → `wait` 는 락을 얻기 위한 대기 시간, `lease` 는 일단 얻은 후 쥐고 있을 시간(TTL). lease 가 짧으면 GC/네트워크 지연으로 락이 빠질 수 있고, 길면 죽은 노드가 락을 영영 쥐고 있을 위험.
2. **Redis 한 노드가 죽으면 어떻게 되나?**
   → 단일 인스턴스라면 분산 락도 죽는다. 운영에서는 Sentinel/Cluster 또는 RedLock 알고리즘으로 보강.

---

# Step 12 — 결제 기능

## 1. 목표

`HOLD` 상태 예약을 결제 → 성공이면 `RESERVED`, 실패면 `HOLD` 해제(좌석 복구).

이 실습에서는 **외부 PG 사 연동을 모의(mock)** 한다 — 90% 확률 성공, 10% 확률 실패.

## 2. PaymentService

`backend/src/main/java/com/seat/backend/payment/PaymentService.java`

```java
package com.seat.backend.payment;

import com.seat.backend.common.BusinessException;
import com.seat.backend.common.ErrorCode;
import com.seat.backend.payment.dto.PaymentRequest;
import com.seat.backend.payment.dto.PaymentResponse;
import com.seat.backend.reservation.Reservation;
import com.seat.backend.reservation.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final ReservationRepository resvRepo;
    private final PaymentRepository payRepo;

    @Transactional
    public PaymentResponse pay(Long userId, PaymentRequest req) {
        Reservation r = resvRepo.findById(req.getReservationId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        // 1. 본인 예약인지 — r.getUser() 는 LAZY 프록시지만 getId() 만 호출하면 추가 SQL 없음
        if (!r.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.RESERVATION_FORBIDDEN);
        }

        // 2. 만료 검사 (TTL 으로도 잡지만 한 번 더 방어)
        if (r.isExpired()) {
            r.cancel();
            r.getSeat().release();   // LAZY 초기화 발생 + 영속 상태 → dirty checking 으로 UPDATE
            throw new BusinessException(ErrorCode.RESERVATION_EXPIRED);
        }

        // 3. 중복 결제 방지 (UNIQUE 제약 + 명시적 검사)
        if (payRepo.existsByReservation_Id(r.getId())) {
            throw new BusinessException(ErrorCode.PAYMENT_DUPLICATED);
        }

        // 4. 결제 행 INSERT — 멱등성 1차 안전망
        Payment p = payRepo.save(Payment.builder()
                .reservation(r)
                .amount(req.getAmount())
                .status(PaymentStatus.REQUESTED)
                .build());

        // 5. 외부 PG 호출 (mock)
        boolean ok = callExternalPg(req.getAmount());

        if (ok) {
            p.success();
            r.confirm();             // HOLD → RESERVED
            r.getSeat().confirm();   // 좌석도 RESERVED
        } else {
            p.fail();
            r.cancel();              // HOLD → CANCELLED
            r.getSeat().release();   // 좌석 복구 → AVAILABLE
            throw new BusinessException(ErrorCode.PAYMENT_FAILED);
        }
        return PaymentResponse.from(p);
    }

    /** 외부 PG 모의 — 90% 성공 */
    private boolean callExternalPg(int amount) {
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return ThreadLocalRandom.current().nextInt(10) != 0;
    }
}
```

## 3. DTO

```java
package com.seat.backend.payment.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Getter
public class PaymentRequest {
    @NotNull private Long reservationId;
    @NotNull @Min(1) private Integer amount;
}
```

```java
package com.seat.backend.payment.dto;

import com.seat.backend.payment.Payment;
import com.seat.backend.payment.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class PaymentResponse {
    private Long paymentId;
    private Long reservationId;
    private PaymentStatus status;
    private LocalDateTime paidAt;

    public static PaymentResponse from(Payment p) {
        // p.getReservation() 은 LAZY 프록시 — getId() 만 호출하면 추가 SQL 없음
        return new PaymentResponse(p.getId(), p.getReservation().getId(), p.getStatus(), p.getPaidAt());
    }
}
```

## 4. PaymentController

```java
package com.seat.backend.payment;

import com.seat.backend.auth.AuthUser;
import com.seat.backend.common.ApiResponse;
import com.seat.backend.payment.dto.PaymentRequest;
import com.seat.backend.payment.dto.PaymentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ApiResponse<PaymentResponse> pay(@RequestBody @Valid PaymentRequest req) {
        Long userId = AuthUser.currentUserId();
        return ApiResponse.ok(paymentService.pay(userId, req));
    }
}
```

## 5. 외부 API 호출과 트랜잭션의 함정

`callExternalPg` 가 **트랜잭션 안**에 있다. 이는 좋은 설계가 아니다 :

- PG 응답이 5초 걸리면 그 동안 DB 커넥션을 점유.
- PG 가 타임아웃나면 트랜잭션은 어떻게? 결제는 성공했는데 내 DB 만 롤백되면 돈이 사라진다.

운영 수준에서는 **결제 요청 INSERT(REQUESTED) → 트랜잭션 커밋 → 외부 호출 → 결과로 별도 트랜잭션에서 SUCCESS/FAILED 업데이트** 의 두 단계 패턴이 안전하다. 이 실습에서는 학습 단순화를 위해 한 트랜잭션에 두지만, 실무 면접에서 반드시 짚고 넘어갈 포인트.

## 6. 흐름 요약

```
[클라]                         [서버]
POST /api/payments  ──►  ① reservation 조회 + 본인 검증 + 만료 검사
                          ② Payment(REQUESTED) INSERT (UNIQUE 로 중복 방지)
                          ③ 외부 PG 호출
                          ④ 성공: Payment.SUCCESS, Reservation.RESERVED, Seat.RESERVED
                             실패: Payment.FAILED, Reservation.CANCELLED, Seat.AVAILABLE
                          ⑤ 응답
```

## 7. 핵심 개념 요약

- **본인 검증**(userId 비교)이 결제의 첫 줄이어야 한다 — 다른 사람의 예약 ID 로 결제하는 일이 절대 없게.
- 결제 row INSERT 전에 UNIQUE 제약으로 중복 결제 가드.
- 결제 실패 → 좌석 `release()` 로 자동 복구.

## 8. 면접 예상 질문

1. **결제 성공 후 DB 커밋 직전 서버가 죽으면?**
   → 외부 PG 는 결제 성공인데 DB 는 롤백. 이런 케이스에 대비해 **결제 결과 조회 API + 정산 잡** 으로 보정하는 reconciliation 패턴이 표준.
2. **결제 요청 row 를 미리 INSERT 하는 이유는?**
   → 동일 요청이 두 번 들어와도 UNIQUE 제약으로 두 번째가 실패하게 만들어 멱등성을 보장. 외부 호출 전에 행이 있어야 추후 정산도 가능.

---

# Step 13 — 예약 만료

## 1. 목표

`HOLD` 상태인 예약이 **5분 안에 결제 안 되면 자동 취소**되도록 한다. 두 가지 방식을 결합한다 :

1. **Redis TTL 키** : 빠르고 정밀, 메인 메커니즘.
2. **`@Scheduled` 보강 잡** : Redis 가 죽거나 키가 유실됐을 때를 위한 백업.

## 2. 설계

```
예약 시점:
   1. Reservation INSERT (HOLD, expires_at = now + 5min)
   2. Redis: SET hold:reservation:{id} 1 EX 300

결제 시점:
   결제 성공 → DEL hold:reservation:{id}

만료 처리 (두 트리거):
   (A) Redis Key Expired Notification 을 듣거나, 또는
   (B) @Scheduled(fixedDelay = 30s) 가 expires_at < now AND status='HOLD' 인 행을 스캔

이 실습은 (B) 만 구현해 단순화. (A) 는 면접 답변에 언급.
```

## 3. ReservationOptimisticService 의 예약 부분에 Redis TTL 키 작성

```java
import org.springframework.data.redis.core.StringRedisTemplate;

@Service
@RequiredArgsConstructor
public class ReservationOptimisticService {

    private final UserRepository userRepo;
    private final SeatRepository seatRepo;
    private final ReservationRepository resvRepo;
    private final StringRedisTemplate redis;

    @Value("${app.reservation.hold-seconds}")
    private long holdSeconds;

    @Transactional
    public ReservationResponse reserveOnce(Long userId, Long seatId) {
        Seat seat = seatRepo.findByIdForUpdate(seatId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND));
        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            throw new BusinessException(ErrorCode.SEAT_NOT_AVAILABLE);
        }
        seat.hold();

        User userRef = userRepo.getReferenceById(userId);
        Reservation r = resvRepo.save(Reservation.builder()
                .user(userRef).seat(seat)
                .status(ReservationStatus.HOLD)
                .expiresAt(LocalDateTime.now().plusSeconds(holdSeconds))
                .build());

        // Redis TTL 키 — 만료 시 사라짐
        redis.opsForValue().set("hold:resv:" + r.getId(), String.valueOf(seatId),
                java.time.Duration.ofSeconds(holdSeconds));

        return ReservationResponse.from(r);
    }
}
```

> 본 실습에선 비관적 락을 디폴트로 쓰므로 `findByIdForUpdate` 를 사용. 분산 락 모드를 쓸 때는 `findById` 로 바꿔도 됨.

## 4. 결제 성공 시 키 삭제

`PaymentService.pay()` 의 성공 분기에 한 줄 추가.

```java
private final StringRedisTemplate redis;
...
if (ok) {
    p.success();
    r.confirm();
    r.getSeat().confirm();
    redis.delete("hold:resv:" + r.getId());     // ← 추가
}
```

## 5. 만료 보강 스케줄러

`backend/src/main/java/com/seat/backend/reservation/ReservationExpireScheduler.java`

```java
package com.seat.backend.reservation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationExpireScheduler {

    private final ReservationRepository resvRepo;

    /** 30초마다 만료 보강 */
    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void releaseExpired() {
        // ReservationRepository 의 @EntityGraph 가 seat 까지 fetch join → N+1 차단
        List<Reservation> targets =
            resvRepo.findAllByStatusAndExpiresAtBefore(ReservationStatus.HOLD, LocalDateTime.now());

        if (targets.isEmpty()) return;
        log.info("만료 예약 {} 건 정리 시작", targets.size());

        for (Reservation r : targets) {
            r.cancel();
            r.getSeat().release();   // 영속 상태이므로 dirty checking 으로 UPDATE
        }
    }
}
```

## 6. 만료 처리의 미묘한 동시성

만료 처리 중 사용자가 결제 API 를 호출하는 케이스를 생각해보자.

```
T1 (스케줄러): SELECT … WHERE expires_at < now AND status=HOLD → 예약 #5 발견
T2 (결제):     reservation #5 조회 → r.isExpired() 검사 통과(아슬아슬)
T1: r.cancel(); seat.release()
T2: r.confirm(); seat.confirm()  ← !!!
```

해결책 :

1. 스케줄러가 좌석에 `findByIdForUpdate` 로 비관적 락을 잡고 처리하면, 결제 트랜잭션은 그 사이 대기.
2. 결제 트랜잭션도 좌석에 `findByIdForUpdate` 를 쓰면 자연스럽게 직렬화.

그래서 위의 비관적 락 기반 예약 + 결제 흐름은 만료 처리와도 잘 맞물린다.

## 7. 핵심 개념 요약

- Redis TTL 은 빠른 만료, **`@Scheduled` 는 안전망** — 두 개 같이 쓴다.
- 만료 처리도 본 흐름과 같은 락 전략을 공유해 race 를 막는다.
- `idx_resv_expires` 인덱스가 없으면 만료 스캔이 풀스캔이 된다 (Step 2 참고).

## 8. 면접 예상 질문

1. **Redis Keyspace Notifications 로 만료 즉시 처리하는 방식은?**
   → `notify-keyspace-events Ex` 설정 후 만료 이벤트를 구독하면 거의 실시간으로 처리 가능. 단, 알림 유실 가능성 + 운영 부담이 있어 보통 스케줄러와 병행.
2. **만료된 예약의 좌석을 `AVAILABLE` 로 되돌릴 때 주의할 점?**
   → 좌석이 그 사이 다른 사람에 의해 다시 HOLD 됐을 가능성이 있으므로, **무조건 release 하지 말고 status 가 여전히 HOLD 일 때만** 풀어야 한다 — `Seat.release()` 가 그 가드를 갖고 있는 이유.

---

# Step 14 — 동시성 통합 테스트

## 1. 목표

세 가지 락 전략 각각에 대해, 100명이 동일 좌석에 동시 예약하면 **단 1명만 성공** 하는지 자동화 테스트.

## 2. 테스트 코드

`backend/src/test/java/com/seat/backend/reservation/ConcurrencyIT.java`

```java
package com.seat.backend.reservation;

import com.seat.backend.event.Seat;
import com.seat.backend.event.SeatRepository;
import com.seat.backend.event.SeatStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ConcurrencyIT {

    @Autowired ReservationOptimisticService optimisticService;       // 비관적 락 사용 (findByIdForUpdate)
    @Autowired ReservationDistributedService distributedService;
    @Autowired ReservationRepository resvRepo;
    @Autowired SeatRepository seatRepo;

    private Long seatId;

    @BeforeEach
    void resetSeat() {
        Seat s = seatRepo.findAll().stream()
            .filter(x -> x.getStatus() != SeatStatus.RESERVED).findFirst().orElseThrow();
        // 직접 SQL 로 초기화하는 게 깔끔하지만, 학습 단순화를 위해 빌더로 재구성
        seatRepo.save(Seat.builder()
            .id(s.getId()).event(s.getEvent()).seatNo(s.getSeatNo())
            .status(SeatStatus.AVAILABLE).version(s.getVersion()).createdAt(s.getCreatedAt())
            .build());
        this.seatId = s.getId();
    }

    @Test
    void 비관적_락_100명_동시_예약_1명만_성공() throws Exception {
        runConcurrent(100, userId -> optimisticService.reserveOnce(userId, seatId));
    }

    @Test
    void 분산_락_100명_동시_예약_1명만_성공() throws Exception {
        runConcurrent(100, userId -> distributedService.reserve(userId, seatId));
    }

    private void runConcurrent(int threads, java.util.function.LongConsumer task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail    = new AtomicInteger();

        for (int i = 1; i <= threads; i++) {
            final long uid = i;
            pool.submit(() -> {
                try {
                    task.accept(uid);
                    success.incrementAndGet();
                } catch (Exception e) {
                    fail.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();

        System.out.printf("success=%d, fail=%d%n", success.get(), fail.get());
        assertThat(success.get()).isEqualTo(1);
        assertThat(resvRepo.findBySeat_IdAndStatus(seatId, ReservationStatus.HOLD)).isPresent();
    }
}
```

## 3. 실행

```bash
./gradlew test --tests "com.seat.backend.reservation.ConcurrencyIT"
```

콘솔 :

```
success=1, fail=99
success=1, fail=99
```

DB 확인 :

```sql
SELECT seat_id, COUNT(*) FROM reservations
 WHERE seat_id = 1 AND status='HOLD'
 GROUP BY seat_id;
-- → 1
```

## 4. 핵심 개념 요약

- 동시성 코드는 **반드시 자동화 테스트**로 검증해야 한다 — 손으로 누르는 시연으로는 race 재현이 어렵다.
- 1명 성공 + 99명 명시적 실패가 정상.

## 5. 면접 예상 질문

1. **테스트가 가끔 통과하고 가끔 깨지는데 어떻게 디버깅?**
   → "Heisenbug" — 보통 트랜잭션 경계, 락 범위, 캐시 영속성 컨텍스트 중 하나의 문제. 로그에 실행 SQL + 스레드 ID 를 찍어 시간순 분석. 실제 운영에서는 `EXPLAIN` 으로 락 동작도 확인.

---

# Step 15 — 배포

## 1. 목표

Spring Boot 애플리케이션을 Docker 이미지로 만들고, AWS EC2 + Nginx + HTTPS(Let's Encrypt) 까지 배포.

## 2. Dockerfile — `backend/Dockerfile`

```dockerfile
# 1단계 — 빌드
FROM gradle:8.10-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN gradle bootJar --no-daemon

# 2단계 — 실행
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
ENV JAVA_OPTS="-Xms256m -Xmx512m"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

## 3. 통합 docker-compose — `docker/docker-compose.yml`

기존 mysql/redis 에 app 서비스 추가.

```yaml
services:
  mysql:
    image: mysql:8.0
    container_name: seat-mysql
    environment:
      MYSQL_ROOT_PASSWORD: root1234
      MYSQL_DATABASE: seatapp
      MYSQL_USER: seat
      MYSQL_PASSWORD: seat1234
      TZ: Asia/Seoul
    ports: ["3306:3306"]
    volumes:
      - seat-mysql-data:/var/lib/mysql
      - ../db/init.sql:/docker-entrypoint-initdb.d/01-init.sql:ro
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_unicode_ci

  redis:
    image: redis:7.2-alpine
    container_name: seat-redis
    ports: ["6379:6379"]
    command: ["redis-server", "--appendonly", "yes"]
    volumes:
      - seat-redis-data:/data

  app:
    build:
      context: ../backend
      dockerfile: Dockerfile
    container_name: seat-app
    depends_on:
      - mysql
      - redis
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/seatapp?useSSL=false&serverTimezone=Asia/Seoul&characterEncoding=UTF-8
      SPRING_DATASOURCE_USERNAME: seat
      SPRING_DATASOURCE_PASSWORD: seat1234
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PORT: 6379
      JWT_SECRET: ${JWT_SECRET:-prod-secret-please-change-me-very-long-key-32-chars-min}
    ports: ["8080:8080"]
    restart: unless-stopped

volumes:
  seat-mysql-data:
  seat-redis-data:
```

> 환경변수 우선순위 덕분에 `application.yml` 의 값이 환경변수로 자연스럽게 덮어씌워진다.

## 4. EC2 배포

```bash
# 1) EC2(Amazon Linux 2023 기준) 에 Docker 설치
sudo dnf install -y docker
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user
# (재로그인)

# 2) docker-compose plugin
sudo dnf install -y docker-compose-plugin

# 3) 코드 가져오기
git clone https://github.com/<your>/seat-platform.git
cd seat-platform/docker
JWT_SECRET="$(openssl rand -hex 32)" docker compose up -d --build

# 4) 동작 확인
curl http://localhost:8080/api/events
```

## 5. Nginx 리버스 프록시 + HTTPS

EC2 호스트(또는 별도 컨테이너) 에 Nginx 설치 :

```bash
sudo dnf install -y nginx
sudo systemctl enable --now nginx
```

`/etc/nginx/conf.d/seat.conf` :

```nginx
server {
    listen 80;
    server_name seat.example.com;

    location / {
        proxy_pass         http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
    }
}
```

```bash
sudo nginx -t && sudo systemctl reload nginx
```

### Let's Encrypt HTTPS

```bash
sudo dnf install -y certbot python3-certbot-nginx
sudo certbot --nginx -d seat.example.com
```

certbot 이 위 conf 를 자동 수정해 443 + 인증서 + 자동 redirect 까지 적용.

## 6. EC2 보안 그룹

| 포트 | 설명 |
|---|---|
| 22  | SSH (관리자 IP 만 허용) |
| 80  | HTTP (certbot 갱신용으로 항상 열어둠) |
| 443 | HTTPS (전체 허용) |
| 3306, 6379 | **외부 닫기** — 같은 VPC 안에서만 |

## 7. 운영에서 한 번 더 챙겨야 할 것

- **DB/Redis 는 EC2 가 아닌 RDS / ElastiCache 로 분리** : 백업 자동, 장애 시 페일오버.
- **로그 수집** : `logback-spring.xml` 에 JSON 포맷 + CloudWatch / Loki 송신.
- **헬스체크** : Spring Actuator `/actuator/health` 를 ALB/Nginx 가 폴링.
- **무중단 배포** : Blue/Green 또는 ECS Rolling.
- **시크릿** : 환경변수 직접 박지 말고 Secrets Manager / Parameter Store.

## 8. 핵심 개념 요약

- 멀티 스테이지 Dockerfile 로 이미지 슬림화.
- compose 의 환경변수가 Spring 환경변수와 자동 매핑.
- Nginx + certbot 한 줄로 HTTPS.
- 운영급은 RDS/ElastiCache + 로그 수집 + 헬스체크 까지가 기본.

## 9. 면접 예상 질문

1. **EC2 한 대에 DB/Redis/App 같이 띄우는 건 왜 안 좋나?**
   → 단일 장애점, 자원 경쟁, 백업/복구 어려움, 보안 분리 안 됨. POC 만 OK.
2. **무중단 배포 시 동시성 락은 어떻게 되나?**
   → 비관적/낙관적 락은 DB 가 들고 있으니 인스턴스 교체에 영향 없음. **분산 락도 Redis 가 외부에 있으니** 인스턴스 교체와 무관. 락 키 안에서만 짧게 머무는 것이 핵심.

---

# 마무리

이 실습서를 완주하면 다음을 손에 익히게 된다 :

- **JWT + Spring Security** 로 무상태 인증 구축
- **JPA 도메인 모델 + 상태 머신** 으로 비즈니스 무결성 표현
- **비관적 락 / 낙관적 락 / Redis 분산 락** 의 차이를 코드 + 테스트로 체감
- **트랜잭션·락·외부 호출 순서**의 함정과 그 해결
- **TTL + Scheduler 이중화**로 만료 안전하게 처리
- **Docker / EC2 / Nginx / HTTPS** 배포

이제 면접에서 "동시 예약 어떻게 막을 거에요?" 라는 질문에 **3가지 방식 + 트레이드오프 + 실제 구현 경험**으로 대답할 수 있다.
