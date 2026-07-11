package com.example.auth.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Returned on successful authentication.
 * accessToken  – use in Authorization: Bearer header for subsequent requests
 * idToken      – contains user claims (email, phone, name, sub, etc.)
 * refreshToken – exchange for a new accessToken when it expires
 */
@Data
@Builder
public class AuthResponse {
    private String accessToken;
    private String idToken;
    private String refreshToken;
    private String tokenType;
    private Integer expiresIn;
}
