# Cognito Auth Microservice – Generation Prompt

Build a Spring Boot 3.2 (Java 21) microservice for AWS Cognito authentication with the following:

## Tech Stack
- Spring Boot 3.2, Spring Security, OAuth2 Resource Server (JWT), Bean Validation, Actuator
- AWS SDK v2 (`cognitoidentityprovider`, `url-connection-client`)
- Springdoc OpenAPI 2.3.0, Lombok
- Maven, packaged as a Docker image on Amazon Corretto 21

**Package:** `com.example.auth`

## Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/auth/login` | Public | Authenticate via `USER_PASSWORD_AUTH` flow → returns `accessToken`, `idToken`, `refreshToken` |
| GET | `/api/auth/validate` | Bearer | Calls Cognito `GetUser` with the access token to confirm it's active → returns `UserProfile` |
| GET | `/api/auth/profile` | Bearer | Returns all JWT claims directly (no AWS call) |
| GET | `/api/users/{username}` | Bearer | Admin lookup via `AdminGetUser` → returns `UserProfile` |

## Classes to Generate

1. `AuthApplication` – `@SpringBootApplication` entry point
2. `AwsConfig` – `@Configuration` bean for `CognitoIdentityProviderClient` using static credentials from `application.yml` (region, access-key, secret-key)
3. `SecurityConfig` – stateless, CSRF disabled; permits login + actuator + Swagger endpoints; all others require JWT; `JwtAuthenticationConverter` grants `ROLE_USER` to all authenticated users
4. `OpenApiConfig` – OpenAPI 3 with `bearerAuth` HTTP security scheme
5. `CognitoService` – `@Service` with three methods:
   - `authenticateUser(LoginRequest)` → `AuthResponse`: builds auth params, optionally adds `SECRET_HASH` (HMAC-SHA256), calls `initiateAuth`, handles `NEW_PASSWORD_REQUIRED` challenge and Cognito exceptions
   - `validateToken(String accessToken)` → `UserProfile`: calls `GetUser`
   - `getUserByUsername(String username)` → `UserProfile`: calls `AdminGetUser`, sets `userStatus`
6. `AuthController` – `@RestController` wiring the above endpoints with Swagger `@Operation` annotations
7. DTOs:
   - `LoginRequest` (`@NotBlank` username + password)
   - `AuthResponse` (accessToken, idToken, refreshToken, tokenType, expiresIn)
   - `UserProfile` (sub, username, email, phoneNumber, name, emailVerified, phoneVerified, userStatus)
   - `ErrorResponse` (errorCode, message, timestamp, path)
8. `CognitoAuthException` – `RuntimeException` with `errorCode` + `HttpStatus`
9. `GlobalExceptionHandler` – `@RestControllerAdvice` handling `CognitoAuthException`, `MethodArgumentNotValidException`, and generic `Exception`

## `application.yml` Properties

```yaml
spring.security.oauth2.resourceserver.jwt.issuer-uri: <cognito-issuer>
spring.security.oauth2.resourceserver.jwt.jwk-set-uri: <cognito-jwks-uri>
aws.region: <region>
aws.cognito.user-pool-id: <user-pool-id>
aws.cognito.client-id: <client-id>
aws.cognito.client-secret: <client-secret>
aws.credentials.access-key: <access-key>
aws.credentials.secret-key: <secret-key>
```

## Dockerfile

`amazoncorretto:21` base image, copies `target/*.jar` to `/app.jar`, exposes `8080`.

## `buildspec.yaml`

AWS CodeBuild spec using Corretto 21, runs `mvn clean package -DskipTests`, builds and pushes Docker image to ECR with `latest` and build-id tags, outputs `imagedefinitions.json`.
