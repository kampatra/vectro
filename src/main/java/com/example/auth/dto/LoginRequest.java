package com.example.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for POST /api/auth/login.
 * The username can be the user's email or phone number —
 * whatever was used to register them in Cognito.
 */
@Data
public class LoginRequest {

    @NotBlank(message = "username is required")
    private String username;   // email or phone number

    @NotBlank(message = "password is required")
    private String password;
}
