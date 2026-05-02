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