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
//컨트롤러에서 현재 로그인 사용자 ID 를 한 줄로 꺼내기 위한 유틸.