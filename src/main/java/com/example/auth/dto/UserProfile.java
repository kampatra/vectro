package com.example.auth.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Flattened user attributes returned by GET /api/auth/validate
 * and GET /api/auth/profile.
 */
@Data
@Builder
public class UserProfile {
    private String sub;           // Cognito user sub (UUID)
    private String username;      // Cognito username
    private String email;
    private String phoneNumber;
    private String name;
    private Boolean emailVerified;
    private Boolean phoneVerified;
    private String userStatus;    // CONFIRMED | FORCE_CHANGE_PASSWORD | etc.
}
