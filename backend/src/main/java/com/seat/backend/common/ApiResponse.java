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