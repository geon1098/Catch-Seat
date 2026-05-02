# 공연 좌석 예약 시스템 (seat-platform)

Spring Boot + MySQL + Redis 로 만든 **콘서트/공연 좌석 예약 백엔드**입니다.
"같은 좌석을 100명이 동시에 누르면 1명만 성공해야 한다" 는 동시성 문제를 직접 부딪혀가며 해결한 학습용 프로젝트입니다.

> 신입 개발자가 처음 동시성 / 트랜잭션 / 비관적 락을 만지면서 적은 작업 일지입니다. 같은 길 걷는 분께 도움이 되길.

---

## 목차

1. [기술 스택](#1-기술-스택)
2. [전체 구조](#2-전체-구조)
3. [실행 방법](#3-실행-방법)
4. [API 사용 가이드 — 회원가입부터 예약까지](#4-api-사용-가이드--회원가입부터-예약까지)
5. [동시성 테스트 — 왜, 어떻게](#5-동시성-테스트--왜-어떻게)
6. [트러블 슈팅 일지](#6-트러블-슈팅-일지)
7. [배운 점 / 어려웠던 점](#7-배운-점--어려웠던-점)

---

## 1. 기술 스택

| 영역 | 사용 기술 |
|---|---|
| 언어 / 프레임워크 | Java 17, Spring Boot 3.5.x |
| ORM | Spring Data JPA (Hibernate) |
| DB | MySQL 8.0 (Docker) |
| 캐시 / 분산 락 | Redis 7.2 + Redisson |
| 인증 | Spring Security + JWT (jjwt 0.12.x) |
| 빌드 | Gradle |
| 테스트 | JUnit 5 + AssertJ + `@SpringBootTest` |

---

## 2. 전체 구조

```
backend/src/main/java/com/seat/backend
 ├─ auth/         회원가입 / 로그인 / JWT 필터
 ├─ event/        공연(Event) + 좌석(Seat)
 ├─ reservation/  예약 도메인 (이번 프로젝트의 핵심)
 ├─ payment/      결제 (다음 단계)
 ├─ user/         유저 엔티티
 ├─ common/       공통 ApiResponse / ErrorCode / 전역 예외 처리
 ├─ config/       SecurityConfig
 └─ web/          ViewController (Thymeleaf 페이지)
```

좌석 상태 전이:

```
AVAILABLE  ──예약 시도──▶  HOLD  ──결제 성공──▶  RESERVED
                          │
                          └──만료 / 결제 실패──▶  AVAILABLE
```

---

## 3. 실행 방법

### 3-1. 사전 준비

- Docker Desktop
- JDK 17
- 포트 3309 (MySQL), 6379 (Redis), 8099 (앱) 비워두기

### 3-2. 인프라 띄우기

```bash
cd c:/seat-platform/docker
docker compose up -d
```

`init.sql` 의 시드 데이터(공연 1개 + 좌석 10개)가 자동으로 들어갑니다.

> ⚠ **주의** — 도커 볼륨이 한 번 만들어진 뒤엔 `init.sql` 이 다시 실행되지 않습니다.
> 시드가 안 보이면 [트러블 슈팅 §6-2](#6-2-좌석이-비어있다--시드데이터-누락) 를 참고.

### 3-3. 앱 실행

```bash
cd c:/seat-platform/backend
./gradlew bootRun
```

`http://localhost:8099` 접속.

### 3-4. 테스트만 따로 돌리기

```bash
./gradlew test --tests "com.seat.backend.reservation.ReservationConcurrencyTest"
```

---

## 4. API 사용 가이드 — 회원가입부터 예약까지

전부 JSON 으로 주고받는 REST API 입니다. 아래 흐름대로 따라하면 됩니다.

### 4-1. 회원가입

```http
POST /api/auth/signup
Content-Type: application/json

{
  "email": "alice@example.com",
  "password": "secret123",
  "nickname": "alice"
}
```

응답:

```json
{ "success": true, "data": 1, "error": null }
```

`data` 값이 새로 만들어진 `userId` 입니다.

### 4-2. 로그인 → JWT 받기

```http
POST /api/auth/login
Content-Type: application/json

{ "email": "alice@example.com", "password": "secret123" }
```

응답:

```json
{
  "success": true,
  "data": { "accessToken": "eyJhbGciOi...." },
  "error": null
}
```

이 `accessToken` 을 이후 요청 헤더에 `Authorization: Bearer eyJhbGciOi....` 형태로 붙여야 예약/결제 API 가 열립니다.

### 4-3. 공연 목록 보기 (비로그인 OK)

```http
GET /api/events
```

```json
{
  "success": true,
  "data": [
    { "id": 1, "title": "재즈 공연 — Spring Night", "startAt": "2026-06-01T20:00:00" }
  ]
}
```

### 4-4. 좌석 목록 보기 (비로그인 OK)

```http
GET /api/events/1/seats
```

```json
{
  "success": true,
  "data": [
    { "id": 1, "seatNo": "A1", "status": "AVAILABLE" },
    { "id": 2, "seatNo": "A2", "status": "AVAILABLE" },
    ...
  ]
}
```

여기서 **마음에 드는 좌석의 `id`** 를 골라서 다음 단계로.

### 4-5. 좌석 예약 (로그인 필요)

```http
POST /api/reservations
Authorization: Bearer eyJhbGciOi....
Content-Type: application/json

{ "seatId": 1 }
```

성공:

```json
{
  "success": true,
  "data": {
    "reservationId": 7,
    "seatId": 1,
    "status": "HOLD",
    "expiresAt": "2026-05-02T14:25:00"
  }
}
```

같은 좌석에 다른 사람이 먼저 들어왔다면:

```json
{
  "success": false,
  "data": null,
  "error": { "code": "SEAT_NOT_AVAILABLE", "message": "이미 예약된 좌석입니다" }
}
```

> 예약은 곧바로 `RESERVED` 가 되지 않고 `HOLD` (5분 임시 점유) 상태로 들어갑니다. 결제까지 끝나야 `RESERVED` 로 확정.

### 4-6. 화면으로 직접 사용

| URL | 페이지 |
|---|---|
| `/` | 공연 목록 |
| `/signup` | 회원가입 |
| `/login` | 로그인 |
| `/seats?eventId=1` | 좌석 선택 |

---

## 5. 동시성 테스트 — 왜, 어떻게

### 5-1. 왜 이 테스트를 만들었나

티켓팅 시스템의 가장 무서운 버그는 **이중 예약**입니다. 같은 A1 좌석에 두 명이 동시에 결제까지 끝내면 누군가는 공연 당일 입장 거부를 당하게 돼요.
사람이 클릭으로 재현하기는 거의 불가능한 버그 — 그래서 **자동화된 동시성 테스트** 가 곧 보험입니다.

테스트의 한 줄 요약:

> "100명이 동시에 같은 좌석을 잡아도 정확히 1명만 성공해야 한다."

### 5-2. 어떻게 검증하나

[ReservationConcurrencyTest.java](src/test/java/com/seat/backend/reservation/ReservationConcurrencyTest.java) 가 합니다.

핵심 도구:

- `ExecutorService` (스레드 풀 32개) — 동시에 돌아가는 알바생들
- `CountDownLatch(100)` — "100개 작업이 다 끝났는지" 확인하는 체크리스트
- `AtomicInteger` — 여러 스레드가 동시에 +1 해도 안전한 카운터

100개 스레드가 같은 `seatId=1` 을 동시에 예약 시도 → 성공 카운터 / 실패 카운터를 센다 → `success=1, fail=99` 면 통과.

핵심 로직 ([ReservationService.java:57-77](src/main/java/com/seat/backend/reservation/ReservationService.java#L57)):

```java
@Transactional
public ReservationResponse reserveWithPessimistic(Long userId, Long seatId) {
    Seat seat = seatRepo.findByIdForUpdate(seatId)   // SELECT ... FOR UPDATE
            .orElseThrow(...);
    if (seat.getStatus() != SeatStatus.AVAILABLE) {
        throw new BusinessException(ErrorCode.SEAT_NOT_AVAILABLE);
    }
    seat.hold();
    // INSERT 예약
    ...
}
```

`@Lock(PESSIMISTIC_WRITE)` + `@Transactional` 한 쌍이 만들어내는 SQL 한 줄(`SELECT ... FOR UPDATE`)이 모든 동시성을 막습니다. 자세한 동작은 [같은좌석_100명_동시예약하면_1명성공테스트.md](같은좌석_100명_동시예약하면_1명성공테스트.md) 참고.

---

## 6. 트러블 슈팅 일지

### 6-1. `TransactionRequiredException: Executing an update/delete query`

**증상**

```
ReservationConcurrencyTest > FAILED
    jakarta.persistence.TransactionRequiredException: Executing an update/delete query
        at com.seat.backend.reservation.ReservationConcurrencyTest.setUp(...)
```

테스트 실행 → success/fail 결과 보기 전에 setUp() 단계에서 멈춤.

**원인**

원래 코드는 이렇게 생겼었어요:

```java
@BeforeEach
@Transactional        // ← 여기에만 붙어 있었음
void setUp() {
    em.createQuery("delete from Reservation ...").executeUpdate();   // 여기서 폭발
    ...
}
```

JPA 의 bulk DML(`executeUpdate()`) 은 **활성 트랜잭션이 없으면 무조건 예외**를 던집니다.
근데 `@Transactional` 을 붙였는데 왜 트랜잭션이 없냐... 이게 핵심 함정이었어요.

**Spring Test 의 동작**

> Spring 의 `TransactionalTestExecutionListener` 는 `@Test` 메서드에 `@Transactional` 이 붙어 있을 때 트랜잭션을 시작하고, 그 트랜잭션을 `@BeforeEach` 까지 확장(propagate) 한다. **`@BeforeEach` 단독으로 `@Transactional` 을 붙여도 트랜잭션이 시작되지 않는다.**

저는 "어노테이션 붙였으니 당연히 트랜잭션이 열리겠지" 라고 생각했는데, Spring 은 `@Test` 가 시작점이지 lifecycle 메서드가 시작점은 아니었어요.
그렇다고 본 테스트 메서드에 `@Transactional` 을 붙일 수도 없습니다 — **그러면 100개 작업이 전부 한 트랜잭션 안에서 돌아 락 다툼 자체가 일어나지 않거든요** (동시성 테스트의 의미가 사라짐).

**해결**

setUp 만 명시적으로 트랜잭션을 열어주는 `TransactionTemplate` 으로 교체:

```java
@Autowired PlatformTransactionManager txManager;

@BeforeEach
void setUp() {
    new TransactionTemplate(txManager).execute(status -> {
        em.createQuery("delete from Reservation ...").executeUpdate();
        em.createQuery("update Seat ...").executeUpdate();
        userRepo.save(...);
        return null;
    });
}
```

이러면 setUp 의 데이터 정리는 트랜잭션 안에서 돌아 커밋되고, 본 테스트 메서드는 트랜잭션 밖에서 돌아 락 다툼이 정상적으로 일어납니다.

### 6-2. 좌석이 비어있다 — 시드데이터 누락

**증상**

```
java.lang.IllegalStateException: seatId=1 좌석이 없습니다.
    at ReservationConcurrencyTest.setUp(ReservationConcurrencyTest.java:58)
```

DB 에 직접 들어가 보면:

```sql
SELECT COUNT(*) FROM seats;  -- 0
```

**원인**

`docker-compose.yml` 에서 `db/init.sql` 을 `docker-entrypoint-initdb.d/` 에 마운트하고 있는데, MySQL 공식 이미지는 **데이터 디렉토리가 비어있을 때만** 이 스크립트를 실행합니다. 한 번 컨테이너를 띄우고 나면 볼륨에 데이터가 생기고, 그 다음부터는 init.sql 이 무시돼요.

저는 처음에 컨테이너만 `down/up` 했더니 시드가 다시 안 들어와서 한참 헤맸습니다.

**해결 방법 두 가지**

A) 볼륨을 통째로 지우고 다시 띄우기 (가장 깔끔):

```bash
docker compose down -v
docker compose up -d
```

B) 그게 무서우면 직접 INSERT (개발 중일 때만):

```bash
docker exec seat-mysql mysql -useat -pseat1234 -D seatapp -e "
  DELETE FROM reservations;
  DELETE FROM seats;
  ALTER TABLE seats AUTO_INCREMENT = 1;
  INSERT INTO seats (event_id, seat_no) VALUES
    (1,'A1'),(1,'A2'),(1,'A3'),(1,'A4'),(1,'A5'),
    (1,'B1'),(1,'B2'),(1,'B3'),(1,'B4'),(1,'B5');
"
```

> ⚠ B 를 쓸 땐 `ALTER TABLE ... AUTO_INCREMENT = 1` 을 꼭 해주세요. 그냥 INSERT 만 하면 이전에 실패한 INSERT 가 ID 를 소비해서 `id=11` 부터 시작합니다 — 테스트는 `seatId=1` 을 찾으니 또 똑같이 깨집니다.

### 6-3. `/favicon.ico` 가 매 요청마다 ERROR 로그를 찍는 문제

**증상**

회원가입 페이지를 띄우기만 해도 콘솔에 빨간 ERROR 가:

```
ERROR --- GlobalExceptionHandler : UNCAUGHT
org.springframework.web.servlet.resource.NoResourceFoundException: No static resource favicon.ico.
    at ResourceHttpRequestHandler.handleRequest(...)
    ... (스택 100줄)
```

**원인**

브라우저는 어떤 페이지를 열든 자동으로 `/favicon.ico` 를 요청합니다. `static/` 폴더에 favicon 파일이 없으면 Spring 이 `NoResourceFoundException` 을 던지는데, 우리 `GlobalExceptionHandler` 의 catch-all `Exception.class` 핸들러가 이걸 잡아서 `log.error("UNCAUGHT", e)` 로 100줄짜리 스택을 찍고 있었어요. 진짜 에러랑 favicon 404 가 똑같이 빨갛게 떠서 진짜 에러를 놓치기 딱 좋은 상황.

**해결**

전용 핸들러를 추가해서 로그 없이 조용히 404 만 내려주게:

```java
@ExceptionHandler(NoResourceFoundException.class)
public ResponseEntity<Void> handleNoResource(NoResourceFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
}
```

브라우저는 어차피 favicon 이 404 라는 응답만 받으면 더 안 물어봐서 깔끔해졌습니다.

---

## 7. 배운 점 / 어려웠던 점

### 7-1. 배운 점

**1) 어노테이션은 "마법" 이 아니라 "약속" 이다**

`@Transactional` 을 붙이면 당연히 트랜잭션이 열린다고 믿었어요. 근데 Spring Test 에서는 *`@Test` 가 시작점일 때만* 그 약속이 지켜집니다. 어노테이션 하나하나가 누구한테 / 언제 인식되는지를 아는 게 진짜 실력이라는 걸 느꼈어요.

**2) 비관적 락 한 줄이 만들어내는 안정감**

`@Lock(LockModeType.PESSIMISTIC_WRITE)` 어노테이션 하나, 그게 만드는 SQL `SELECT ... FOR UPDATE` 한 줄이 100명의 동시 요청을 정확히 한 명만 통과시킨다는 게 신기했어요. DB 가 이렇게 강력한 도구라는 걸 처음 체감했습니다.

**3) 동시성 버그는 단위 테스트로 못 잡는다**

처음엔 "Mockito 로 SeatRepository 를 mock 해서 테스트하면 되지 않나?" 했는데, mock 으로는 락이 흉내가 안 나요. 진짜 DB 를 띄우는 통합 테스트(`@SpringBootTest`) 가 아니면 이중예약 버그를 절대 못 잡습니다.

**4) `AtomicInteger` 와 `CountDownLatch` 를 처음 써봤다**

100개 스레드가 동시에 `int success++` 를 하면 결과가 73 같은 이상한 숫자가 나옵니다 (검증된 race condition). `AtomicInteger.incrementAndGet()` 은 CPU 의 CAS 명령어로 그걸 막아준다는 걸 배웠어요. `CountDownLatch` 도 마찬가지로 "100개 작업이 다 끝나면 깨워줘" 를 표현하는 정석 도구.

**5) 전역 예외 처리는 "잡으면 끝" 이 아니다**

`@ExceptionHandler(Exception.class)` 로 전부 잡아서 ERROR 로그 찍으면 깔끔할 것 같지만, 진짜 에러랑 무해한 404(`favicon.ico`) 가 같이 빨갛게 떠서 오히려 진짜 문제를 못 봅니다. **에러의 종류를 구분해서 다르게 다루는 것** 이 GlobalExceptionHandler 의 진짜 역할이라는 걸 알았어요.

### 7-2. 어려웠던 점

**1) "왜 안 되는지" 가 안 보일 때가 가장 힘들다**

`@Transactional` 이 안 먹는 이유를 한참 검색했어요. "어노테이션 붙였는데 왜 안 되지?" 라는 질문은 검색어를 잡기가 정말 어렵습니다. 결국 예외 메시지(`TransactionRequiredException`) 를 그대로 검색해서 Stack Overflow 답변에서 "Spring Test 의 `@Transactional` 은 lifecycle 메서드에 단독으로 붙으면 동작이 다르다" 는 글을 찾고 풀렸어요. **에러 메시지를 정확히 읽는 것** 이 디버깅의 시작.

**2) 도커 볼륨의 동작**

`init.sql` 이 한 번만 실행된다는 사실을 모르고 `docker compose down/up` 만 반복하다 시드가 안 들어와서 1시간을 헤맸어요. `docker compose down -v` (볼륨까지 같이 지우기) 의 `-v` 가 그렇게 중요한지 처음 알았습니다.

**3) AUTO_INCREMENT 의 잔존**

INSERT 가 실패해도 AUTO_INCREMENT 카운터는 올라간다는 사실. 테스트 도중 ID 가 11부터 시작해서 `seatId=1 좌석이 없습니다` 가 떴을 때 "분명 INSERT 했는데 왜 1번이 없지?" 하고 한참 봤어요. `ALTER TABLE ... AUTO_INCREMENT = 1` 로 리셋해야 한다는 걸 배웠습니다.

**4) "테스트 메서드 자체에 @Transactional 을 붙이면 안 된다" 라는 반직관**

평소엔 테스트에 `@Transactional` 붙여서 자동 롤백시키는 게 좋은 습관이라고 배웠는데, 동시성 테스트만큼은 정반대였어요. 한 트랜잭션 안에서는 락 다툼이 일어날 일이 없어서 테스트가 의미를 잃거든요. **상황마다 맞는 도구가 다르다** 는 평범한 진리를 또 한 번 새깁니다.

---

## 마무리

이 프로젝트의 핵심은 사실 **단 두 줄** 입니다:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Transactional
```

이 두 줄을 정확한 위치에 붙이고, 그게 진짜로 동작하는지 100명이 달려드는 테스트로 매 빌드마다 검증하는 것 — 그게 이 코드베이스가 보장하는 전부입니다.
나중에 누군가 "성능 좀 더 짜자" 며 `@Transactional` 을 떼거나 락 모드를 바꾸면, 테스트가 빨갛게 깨지면서 **"하지 마, 이중 예약 난다"** 라고 자동으로 알려줄 거예요. 그 보험이 곧 좋은 테스트라고 생각합니다.
