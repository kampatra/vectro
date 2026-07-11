package com.example.auth.controller;

import com.example.auth.dto.AuthResponse;
import com.example.auth.dto.LoginRequest;
import com.example.auth.dto.UserProfile;
import com.example.auth.service.CognitoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Authentication REST controller.
 *
 * POST /api/auth/login          – authenticate with username + password → returns JWT tokens
 * GET  /api/auth/validate       – verify the Bearer token is active via Cognito GetUser
 * GET  /api/auth/profile        – extract claims from the JWT (no AWS round-trip)
 * GET  /api/users/{username}    – admin: look up any user by username
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Cognito user validation APIs")
public class AuthController {

    private final CognitoService cognitoService;

    // ── Public endpoint ───────────────────────────────────────────────────────

    @Operation(summary = "Login with username and password",
               description = "Authenticate against Cognito using USER_PASSWORD_AUTH flow. "
                           + "Returns AccessToken, IdToken, and RefreshToken.")
    @PostMapping("/api/auth/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest loginRequest) {
        log.info("Login request for user: {}", loginRequest.getUsername());
        return ResponseEntity.ok(cognitoService.authenticateUser(loginRequest));
    }

    // ── Protected endpoints ───────────────────────────────────────────────────

    /**
     * Calls Cognito GetUser with the provided access token.
     * If the token is valid and not expired, Cognito returns the user's attributes.
     * This is the authoritative way to confirm a token is still active.
     */
    @Operation(summary = "Validate access token",
               description = "Calls Cognito GetUser to confirm the token is active "
                           + "and returns the user's profile attributes.")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/api/auth/validate")
    public ResponseEntity<UserProfile> validate(
            @RequestHeader("Authorization") String authHeader) {

        String token = authHeader.startsWith("Bearer ")
                ? authHeader.substring(7)
                : authHeader;

        return ResponseEntity.ok(cognitoService.validateToken(token));
    }

    /**
     * Returns the claims embedded in the Cognito JWT without an extra AWS call.
     * Spring Security has already validated the signature and expiry via JWKS.
     *
     * Useful claims in a Cognito idToken / accessToken:
     *   sub, email, phone_number, name, cognito:username, cognito:groups, token_use
     */
    @Operation(summary = "Get profile from JWT claims",
               description = "Returns all claims from the validated JWT — no additional "
                           + "AWS call is made.")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/api/auth/profile")
    public ResponseEntity<Map<String, Object>> profile(@AuthenticationPrincipal Jwt jwt) {
        log.debug("Profile requested for sub: {}", jwt.getSubject());
        return ResponseEntity.ok(jwt.getClaims());
    }

    /**
     * Admin lookup: find any registered user by their Cognito username.
     * The username can be the user's email or phone number.
     * Requires the IAM role to have cognito-idp:AdminGetUser permission.
     */
    @Operation(summary = "Get user by username (admin)",
               description = "Admin lookup using AdminGetUser. The username can be the "
                           + "user's email or phone number as registered in Cognito.")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/api/users/{username}")
    public ResponseEntity<UserProfile> getUser(@PathVariable String username) {
        log.info("Admin user lookup : {}", username);
        return ResponseEntity.ok(cognitoService.getUserByUsername(username));
    }
}
