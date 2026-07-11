package com.example.auth.service;

import com.example.auth.dto.AuthResponse;
import com.example.auth.dto.LoginRequest;
import com.example.auth.dto.UserProfile;
import com.example.auth.exception.CognitoAuthException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * All Cognito interactions live here.
 *
 * Flow:
 *  1. authenticateUser()  – USER_PASSWORD_AUTH → returns JWT tokens
 *  2. validateToken()     – calls Cognito GetUser with the accessToken to confirm it is active
 *  3. getUserByUsername() – admin lookup of any user by username (email / phone)
 */
@Slf4j
@Service
public class CognitoService {

    private final CognitoIdentityProviderClient cognitoClient;

    @Value("${aws.cognito.user-pool-id}")
    private String userPoolId;

    @Value("${aws.cognito.client-id}")
    private String clientId;

    @Value("${aws.cognito.client-secret:}")
    private String clientSecret;   // empty string if app client has no secret

    public CognitoService(CognitoIdentityProviderClient cognitoClient) {
        this.cognitoClient = cognitoClient;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Authenticate (login with email/phone + password)
    // ─────────────────────────────────────────────────────────────────────────

    public AuthResponse authenticateUser(LoginRequest loginRequest) {
        log.debug("Authenticating user: {}", loginRequest.getUsername());

        Map<String, String> authParams = new HashMap<>();
        authParams.put("USERNAME", loginRequest.getUsername());
        authParams.put("PASSWORD", loginRequest.getPassword());

        // Add SECRET_HASH only when the app client has a secret configured
        if (!clientSecret.isBlank()) {
            authParams.put("SECRET_HASH",
                    calculateSecretHash(loginRequest.getUsername()));
        }

        try {
            InitiateAuthRequest authRequest = InitiateAuthRequest.builder()
                    .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                    .clientId(clientId)
                    .authParameters(authParams)
                    .build();

            InitiateAuthResponse authResponse = cognitoClient.initiateAuth(authRequest);

            // Handle NEW_PASSWORD_REQUIRED challenge (first login with temp password)
            if (authResponse.challengeName() == ChallengeNameType.NEW_PASSWORD_REQUIRED) {
                throw new CognitoAuthException(
                        "NEW_PASSWORD_REQUIRED",
                        "User must set a new password before logging in",
                        HttpStatus.FORBIDDEN);
            }

            AuthenticationResultType result = authResponse.authenticationResult();
            return AuthResponse.builder()
                    .accessToken(result.accessToken())
                    .idToken(result.idToken())
                    .refreshToken(result.refreshToken())
                    .tokenType(result.tokenType())
                    .expiresIn(result.expiresIn())
                    .build();

        } catch (NotAuthorizedException e) {
            throw new CognitoAuthException("INVALID_CREDENTIALS",
                    "Incorrect username or password", HttpStatus.UNAUTHORIZED);
        } catch (UserNotFoundException e) {
            throw new CognitoAuthException("USER_NOT_FOUND",
                    "No user found with the provided username", HttpStatus.NOT_FOUND);
        } catch (UserNotConfirmedException e) {
            throw new CognitoAuthException("USER_NOT_CONFIRMED",
                    "User account is not confirmed", HttpStatus.FORBIDDEN);
        } catch (TooManyRequestsException e) {
            throw new CognitoAuthException("RATE_LIMITED",
                    "Too many requests – please try again later", HttpStatus.TOO_MANY_REQUESTS);
        } catch (CognitoIdentityProviderException e) {
            log.error("Cognito error during authentication", e);
            throw new CognitoAuthException("COGNITO_ERROR",
                    "Authentication service error: " + e.awsErrorDetails().errorMessage(),
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Validate an Access Token by calling Cognito GetUser
    //    Returns the user's attributes if the token is valid and not expired.
    // ─────────────────────────────────────────────────────────────────────────

    public UserProfile validateToken(String accessToken) {
        log.debug("Validating access token via Cognito GetUser");
        try {
            GetUserResponse response = cognitoClient.getUser(
                    GetUserRequest.builder().accessToken(accessToken).build());

            return buildUserProfile(response.username(), response.userAttributes());

        } catch (NotAuthorizedException e) {
            throw new CognitoAuthException("INVALID_TOKEN",
                    "Access token is invalid or expired", HttpStatus.UNAUTHORIZED);
        } catch (UserNotFoundException e) {
            throw new CognitoAuthException("USER_NOT_FOUND",
                    "User associated with this token no longer exists", HttpStatus.NOT_FOUND);
        } catch (CognitoIdentityProviderException e) {
            log.error("Cognito error during token validation", e);
            throw new CognitoAuthException("COGNITO_ERROR",
                    "Token validation service error", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Admin lookup – get any user by username (requires AdminGetUser permission)
    // ─────────────────────────────────────────────────────────────────────────

    public UserProfile getUserByUsername(String username) {
        log.debug("Admin lookup for user: {}", username);
        try {
            AdminGetUserResponse response = cognitoClient.adminGetUser(
                    AdminGetUserRequest.builder()
                            .userPoolId(userPoolId)
                            .username(username)
                            .build());

            UserProfile profile = buildUserProfile(response.username(), response.userAttributes());
            profile.setUserStatus(response.userStatusAsString());
            return profile;

        } catch (UserNotFoundException e) {
            throw new CognitoAuthException("USER_NOT_FOUND",
                    "User '" + username + "' does not exist in the User Pool",
                    HttpStatus.NOT_FOUND);
        } catch (CognitoIdentityProviderException e) {
            log.error("Cognito error during admin user lookup", e);
            throw new CognitoAuthException("COGNITO_ERROR",
                    "User lookup service error", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private UserProfile buildUserProfile(String username,
                                          java.util.List<AttributeType> attributes) {
        Map<String, String> attrs = new HashMap<>();
        attributes.forEach(a -> attrs.put(a.name(), a.value()));

        return UserProfile.builder()
                .username(username)
                .sub(attrs.get("sub"))
                .email(attrs.get("email"))
                .phoneNumber(attrs.get("phone_number"))
                .name(attrs.get("name"))
                .emailVerified(Boolean.parseBoolean(attrs.getOrDefault("email_verified", "false")))
                .phoneVerified(Boolean.parseBoolean(attrs.getOrDefault("phone_number_verified", "false")))
                .build();
    }

    /**
     * Required when the Cognito App Client has a client secret configured.
     * SECRET_HASH = Base64(HMAC-SHA256(username + clientId, clientSecret))
     */
    private String calculateSecretHash(String username) {
        try {
            String message = username + clientId;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    clientSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder()
                    .encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate Cognito secret hash", e);
        }
    }
}
