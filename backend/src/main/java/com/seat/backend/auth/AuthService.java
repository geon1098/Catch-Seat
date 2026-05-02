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