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