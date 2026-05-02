package com.seat.backend.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /**
     * 정적 리소스 미존재 — 브라우저가 자동 요청하는 /favicon.ico 등이 여기 걸린다.
     * UNCAUGHT 로 ERROR 로그를 남길 게 아니므로 조용히 404 만 돌려준다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    /** 그 외 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleEtc(Exception e) {
        log.error("UNCAUGHT", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR));
    }
}