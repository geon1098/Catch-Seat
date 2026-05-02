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