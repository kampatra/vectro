# Requirements Document

## Introduction

This document specifies the functional and non-functional requirements for the **Login OTP Authentication Microservice** — a production-ready, passwordless authentication service built with Spring Boot 3.x (Java 21) and AWS Cognito. The microservice orchestrates OTP-based authentication via AWS Cognito custom auth flows, delivering one-time passcodes through AWS SNS (SMS) or SES (email). It exposes six REST API endpoints and is containerized for deployment on AWS ECS Fargate with a GitHub Actions CI/CD pipeline.

## Glossary

- **Auth_Service**: The Spring Boot microservice (`login-otp-auth-microservice`) that acts as the orchestration layer for OTP authentication
- **Cognito**: AWS Cognito Identity Provider (User Pool) that manages identities, custom auth challenges, and JWT token issuance
- **OTP**: One-Time Passcode — a 6-digit numeric code with a 300-second TTL used as the sole authentication factor
- **Session**: The opaque session string returned by Cognito after initiating the custom auth challenge; binds a login attempt to its corresponding OTP validation
- **Identifier**: A user's unique identity — either a mobile phone number (E.164 format) or an email address
- **IdentifierType**: Enum with values `MOBILE` or `EMAIL` indicating the type of identifier provided
- **AccessToken**: A short-lived (60-minute) JWT issued by Cognito granting API access
- **IdToken**: A short-lived (60-minute) JWT issued by Cognito containing user claims
- **RefreshToken**: A long-lived (30-day) token used to obtain new Access and ID tokens
- **Bearer_Token**: An `Authorization: Bearer <accessToken>` HTTP header used to authenticate protected requests
- **Masking_Util**: The utility component responsible for masking identifiers before returning them in responses
- **Global_Exception_Handler**: The `@RestControllerAdvice` component that maps all exceptions to the standard `ErrorResponse` envelope
- **Rate_Limiter**: The component that enforces per-identifier OTP send limits (maximum 3 per 10-minute rolling window)
- **Circuit_Breaker**: The Resilience4j circuit breaker wrapping Cognito SDK calls to prevent cascading failures
- **Lambda_Trigger**: One of three AWS Lambda functions (`DefineAuthChallenge`, `CreateAuthChallenge`, `VerifyAuthChallengeResponse`) wired to the Cognito User Pool
- **ECS_Fargate**: AWS Elastic Container Service with Fargate launch type — the recommended deployment target
- **ALB**: AWS Application Load Balancer fronting the ECS service for TLS termination and health-based routing

---

## Requirements

### Requirement 1: OTP Login Initiation

**User Story:** As a registered user, I want to initiate login using my mobile number or email address, so that I receive an OTP to authenticate without a password.

#### Acceptance Criteria

1. WHEN a `POST /api/v1/auth/login` request is received with a valid `identifier` and `identifierType`, THE Auth_Service SHALL initiate a Cognito custom auth challenge and return a `LoginResponse` containing a non-blank `session`, `maskedIdentifier`, `expiresIn` of 300 seconds, and `challengeName` of `"CUSTOM_CHALLENGE"`
2. WHEN `identifierType` is `MOBILE`, THE Auth_Service SHALL validate the `identifier` against the pattern `^\+[1-9]\d{7,14}$` and return HTTP 400 with `errorCode: INVALID_INPUT` if validation fails
3. WHEN `identifierType` is `EMAIL`, THE Auth_Service SHALL validate the `identifier` conforms to RFC 5321 email format and return HTTP 400 with `errorCode: INVALID_INPUT` if validation fails
4. WHEN Cognito returns a `UserNotFoundException`, THE Auth_Service SHALL return HTTP 404 with `errorCode: USER_NOT_FOUND`
5. WHEN Cognito indicates the user is disabled, THE Auth_Service SHALL return HTTP 403 with `errorCode: USER_DISABLED`
6. WHEN an identifier has triggered 3 or more OTP sends within any rolling 10-minute window, THE Auth_Service SHALL return HTTP 429 with `errorCode: RATE_LIMIT_EXCEEDED` on the next request
7. WHEN Cognito is unreachable or returns a service error, THE Auth_Service SHALL return HTTP 503 with `errorCode: COGNITO_UNAVAILABLE`

### Requirement 2: Identifier Masking

**User Story:** As a user, I want my identifier masked in the login response, so that my full phone number or email address is not exposed over the network.

#### Acceptance Criteria

1. WHEN the login response includes a mobile phone `maskedIdentifier`, THE Masking_Util SHALL preserve the `+` prefix and last 4 digits, replacing all intermediate digits with `*`
2. WHEN the login response includes an email `maskedIdentifier`, THE Masking_Util SHALL preserve only the first character of the local part and the full domain, replacing all other characters before `@` with `*`
3. THE Masking_Util SHALL never expose more than 4 trailing digits of any phone number in any API response
4. THE Masking_Util SHALL never expose more than the first character of the local part of any email address in any API response

### Requirement 3: OTP Validation

**User Story:** As a user who has received an OTP, I want to submit the OTP along with my session token, so that I receive JWT tokens to authenticate subsequent requests.

#### Acceptance Criteria

1. WHEN a `POST /api/v1/auth/validate-otp` request is received with a valid `session` and a correct 6-digit `otp`, THE Auth_Service SHALL submit the challenge response to Cognito and return HTTP 200 with a `TokenResponse` containing non-null `accessToken`, `idToken`, `refreshToken`, `tokenType` of `"Bearer"`, and `expiresIn` of 3600
2. WHEN the `otp` field does not match the pattern `^\d{6}$`, THE Auth_Service SHALL return HTTP 400 with `errorCode: INVALID_INPUT` without calling Cognito
3. WHEN Cognito rejects the OTP as incorrect, THE Auth_Service SHALL return HTTP 400 with `errorCode: INVALID_OTP`
4. WHEN the Cognito session has exceeded its TTL, THE Auth_Service SHALL return HTTP 400 with `errorCode: OTP_EXPIRED`
5. WHEN the user has exceeded the maximum number of failed OTP attempts, THE Auth_Service SHALL return HTTP 400 with `errorCode: MAX_RETRY_EXCEEDED`
6. WHEN Cognito returns an `authenticationResult` after challenge submission, THE Auth_Service SHALL return all three tokens (`accessToken`, `idToken`, `refreshToken`) as valid JWTs signed by the Cognito User Pool JWKS endpoint

### Requirement 4: OTP Resend

**User Story:** As a user who did not receive an OTP or whose OTP expired, I want to request a fresh OTP, so that I can complete authentication without starting over.

#### Acceptance Criteria

1. WHEN a `POST /api/v1/auth/resend-otp` request is received with a valid identifier, THE Auth_Service SHALL initiate a new Cognito auth challenge and return HTTP 200 with a new `LoginResponse` containing a fresh `session` token
2. WHEN a resend is issued, THE Auth_Service SHALL invalidate any previously issued session for the same identifier, such that the old session can no longer be used for OTP validation
3. WHEN an identifier has triggered 3 or more OTP sends (including resends) within any rolling 10-minute window, THE Auth_Service SHALL return HTTP 429 with `errorCode: RATE_LIMIT_EXCEEDED`
4. IF the identifier does not exist in Cognito, THEN THE Auth_Service SHALL return HTTP 404 with `errorCode: USER_NOT_FOUND`

### Requirement 5: Token Refresh

**User Story:** As an authenticated user with an expiring access token, I want to exchange my refresh token for new access and ID tokens, so that I remain authenticated without re-entering an OTP.

#### Acceptance Criteria

1. WHEN a `POST /api/v1/auth/refresh` request is received with a valid `refreshToken`, THE Auth_Service SHALL call Cognito `REFRESH_TOKEN_AUTH` and return HTTP 200 with a `TokenResponse` containing a new `accessToken`, `idToken`, `expiresIn` of 3600, and `refreshToken` as `null`
2. WHEN the `refreshToken` is expired or revoked, THE Auth_Service SHALL return HTTP 401 with `errorCode: UNAUTHORIZED`
3. THE Auth_Service SHALL return `refreshToken` as `null` in all token refresh responses

### Requirement 6: Logout

**User Story:** As an authenticated user, I want to log out and invalidate all my active tokens, so that my session cannot be hijacked if a token is compromised.

#### Acceptance Criteria

1. WHEN a `POST /api/v1/auth/logout` request is received with a valid `Authorization: Bearer <accessToken>` header, THE Auth_Service SHALL call Cognito `GlobalSignOut` and return HTTP 200 with `{"message": "Successfully logged out"}`
2. WHEN a `POST /api/v1/auth/logout` request is received without an `Authorization` header or with a malformed Bearer token, THE Auth_Service SHALL return HTTP 401 with `errorCode: UNAUTHORIZED`
3. WHEN `logout` succeeds for a user, THE Auth_Service SHALL ensure subsequent requests authenticated with any previously issued `accessToken` for that session return HTTP 401

### Requirement 7: User Status

**User Story:** As an authenticated API consumer, I want to retrieve the current account status of the authenticated user, so that I can verify account state before performing sensitive operations.

#### Acceptance Criteria

1. WHEN a `GET /api/v1/auth/status` request is received with a valid `Authorization: Bearer <accessToken>` header, THE Auth_Service SHALL call Cognito `GetUser` and return HTTP 200 with a `UserStatusResponse` containing `sub`, `username`, `email`, `phoneNumber`, `status`, `enabled`, `createdAt`, and `lastModifiedAt`
2. WHEN a `GET /api/v1/auth/status` request is received without a valid Bearer token, THE Auth_Service SHALL return HTTP 401 with `errorCode: UNAUTHORIZED`
3. WHEN the authenticated user does not exist in Cognito, THE Auth_Service SHALL return HTTP 404 with `errorCode: USER_NOT_FOUND`

### Requirement 8: Cognito Exception Mapping

**User Story:** As an API consumer, I want all AWS Cognito errors normalized to a consistent error response format, so that I can handle errors without Cognito-specific knowledge.

#### Acceptance Criteria

1. WHEN Cognito throws `UserNotFoundException`, THE Global_Exception_Handler SHALL map it to HTTP 404 with `errorCode: USER_NOT_FOUND`
2. WHEN Cognito throws `NotAuthorizedException` with message containing "disabled", THE Global_Exception_Handler SHALL map it to HTTP 403 with `errorCode: USER_DISABLED`
3. WHEN Cognito throws `NotAuthorizedException` with message containing "attempt limit", THE Global_Exception_Handler SHALL map it to HTTP 400 with `errorCode: MAX_RETRY_EXCEEDED`
4. WHEN Cognito throws `NotAuthorizedException` for any other reason, THE Global_Exception_Handler SHALL map it to HTTP 400 with `errorCode: INVALID_OTP`
5. WHEN Cognito throws `TooManyRequestsException`, THE Global_Exception_Handler SHALL map it to HTTP 429 with `errorCode: RATE_LIMIT_EXCEEDED`
6. WHEN Cognito throws `ExpiredCodeException`, THE Global_Exception_Handler SHALL map it to HTTP 400 with `errorCode: OTP_EXPIRED`
7. WHEN Cognito throws `CodeMismatchException`, THE Global_Exception_Handler SHALL map it to HTTP 400 with `errorCode: INVALID_OTP`
8. WHEN any unhandled exception occurs, THE Global_Exception_Handler SHALL return HTTP 500 with `errorCode: INTERNAL_ERROR` and message `"An unexpected error occurred"`
9. WHEN any `MethodArgumentNotValidException` occurs, THE Global_Exception_Handler SHALL return HTTP 400 with `errorCode: INVALID_INPUT` and a semicolon-separated list of field validation messages

### Requirement 9: Resilience and Fault Tolerance

**User Story:** As a system operator, I want the microservice to tolerate transient Cognito failures gracefully, so that brief AWS outages do not cause cascading failures for end users.

#### Acceptance Criteria

1. WHEN the Circuit_Breaker for Cognito is configured, THE Auth_Service SHALL retry failed Cognito calls up to 3 times with exponential back-off starting at 500ms and a multiplier of 2
2. WHEN the Circuit_Breaker sliding window records a failure rate above 50% across the last 10 calls (minimum 5 calls), THE Auth_Service SHALL open the circuit for 30 seconds and return HTTP 503 with `errorCode: COGNITO_UNAVAILABLE` for all subsequent Cognito calls during the open state
3. WHEN a Cognito call fails after all retries and the circuit is open, THE Auth_Service SHALL invoke the fallback method and return a structured error response rather than propagating an unhandled exception

### Requirement 10: Security Configuration

**User Story:** As a security engineer, I want the microservice to enforce authentication on all sensitive endpoints and validate JWTs against the Cognito JWKS endpoint, so that only valid token holders can access protected resources.

#### Acceptance Criteria

1. THE Auth_Service SHALL permit unauthenticated access to `POST /api/v1/auth/login`, `POST /api/v1/auth/validate-otp`, `POST /api/v1/auth/resend-otp`, and `POST /api/v1/auth/refresh`
2. THE Auth_Service SHALL permit unauthenticated access to `/actuator/health`, `/actuator/info`, `/swagger-ui/**`, and `/v3/api-docs/**`
3. THE Auth_Service SHALL require a valid Cognito-issued JWT Bearer token for all other endpoints including `POST /api/v1/auth/logout` and `GET /api/v1/auth/status`
4. THE Auth_Service SHALL validate JWT tokens using the Cognito User Pool JWKS endpoint at `https://cognito-idp.{region}.amazonaws.com/{userPoolId}/.well-known/jwks.json`
5. THE Auth_Service SHALL use stateless session management (no server-side HTTP sessions)
6. THE Auth_Service SHALL disable CSRF protection for the stateless REST API
7. THE Auth_Service SHALL configure CORS to allow requests from configured origins with methods `GET`, `POST`, `OPTIONS`

### Requirement 11: Configuration and Secrets Management

**User Story:** As a DevOps engineer, I want all sensitive configuration fetched from AWS Secrets Manager and Parameter Store at startup, so that no secrets are baked into the container image or environment variables.

#### Acceptance Criteria

1. THE Auth_Service SHALL retrieve the Cognito User Pool ID and Client ID from AWS Parameter Store under the path `/auth/cognito/`
2. THE Auth_Service SHALL retrieve the Cognito Client Secret from AWS Secrets Manager under the name `auth/cognito-client-secret`
3. THE Auth_Service SHALL use the AWS instance profile (task role) for all AWS SDK credential resolution and SHALL NOT use static access key/secret pairs in configuration files
4. WHEN a required Parameter Store or Secrets Manager value is missing at startup, THE Auth_Service SHALL fail to start with a descriptive error message
5. THE Auth_Service SHALL support environment-specific configuration profiles (`dev`, `staging`, `prod`) via Spring profiles

### Requirement 12: Observability

**User Story:** As a system operator, I want structured logs and CloudWatch metrics emitted for all authentication events, so that I can monitor OTP success rates, latency, and error rates in production.

#### Acceptance Criteria

1. THE Auth_Service SHALL emit structured JSON logs for every inbound API request and outbound Cognito call at INFO level
2. THE Auth_Service SHALL log all exceptions at ERROR level with stack traces
3. THE Auth_Service SHALL export custom metrics to CloudWatch under the namespace `AuthMicroservice`, including OTP send count, OTP success rate, and request latency
4. THE Auth_Service SHALL expose a `/actuator/health` endpoint that returns HTTP 200 when the service is healthy and includes dependency health details
5. THE Auth_Service SHALL expose a `/actuator/metrics` and `/actuator/prometheus` endpoint for metrics scraping
6. WHEN deployed to ECS Fargate, THE Auth_Service SHALL write logs to the `/ecs/auth-microservice` CloudWatch log group using the `awslogs` log driver

### Requirement 13: Containerization

**User Story:** As a DevOps engineer, I want the microservice packaged as a minimal, secure Docker image, so that it can be deployed consistently across environments.

#### Acceptance Criteria

1. THE Auth_Service SHALL be packaged as a multi-stage Docker image using `maven:3.9-eclipse-temurin-21` for build and `eclipse-temurin:21-jre-alpine` for the runtime stage
2. THE Auth_Service SHALL run as a non-root user (`appuser` in `appgroup`) inside the container
3. THE Auth_Service SHALL expose port `8080` and include a `HEALTHCHECK` that polls `/actuator/health` every 30 seconds with a 5-second timeout, 30-second start period, and 3 retries
4. THE Auth_Service SHALL use JVM flags `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0` in the container entrypoint
5. THE Auth_Service container image SHALL be scannable by Trivy with no critical vulnerabilities before deployment

### Requirement 14: ECS Fargate Deployment

**User Story:** As a DevOps engineer, I want the service deployed on AWS ECS Fargate with an Application Load Balancer, so that I have a fully managed, scalable production environment without managing EC2 instances.

#### Acceptance Criteria

1. THE Auth_Service ECS task definition SHALL allocate 512 CPU units and 1024 MB memory per task
2. THE Auth_Service ECS service SHALL run 2 desired tasks across private subnets with `assignPublicIp: DISABLED`
3. THE Auth_Service ECS service SHALL use a rolling deployment configuration with `maximumPercent: 200` and `minimumHealthyPercent: 100`
4. THE Auth_Service SHALL be fronted by an ALB Target Group using `/actuator/health` as the health check path with a 30-second interval
5. THE Auth_Service SHALL use an ECS task role (`authMicroserviceTaskRole`) with least-privilege IAM policies granting only the required Cognito, Secrets Manager, SSM, CloudWatch, and CloudWatch Logs actions
6. WHEN THE Auth_Service ECS service is updated with a new task definition, THE deployment SHALL wait for service stability before marking the deployment as complete

### Requirement 15: CI/CD Pipeline

**User Story:** As a developer, I want automated build, test, security scan, and deployment pipelines via GitHub Actions, so that every commit to `main` is tested and deployed safely.

#### Acceptance Criteria

1. WHEN a pull request is opened against `main` or a push is made to `main` or `develop`, THE CI pipeline SHALL execute Maven build (`mvn clean verify`), run all tests, generate a JaCoCo coverage report, build a Docker image, and run a Trivy security scan
2. WHEN a push is made to `main`, THE CD pipeline SHALL build the application, authenticate to AWS ECR using OIDC (no stored credentials), push the Docker image tagged with the commit SHA and `latest`, update the ECS task definition, and deploy to the ECS cluster
3. WHEN THE CD pipeline deploys to ECS, THE pipeline SHALL wait for ECS service stability before marking the deployment successful
4. THE CI pipeline SHALL upload Trivy scan results as SARIF to GitHub Code Scanning
5. THE CI pipeline SHALL upload JaCoCo coverage reports to Codecov

### Requirement 16: Cognito User Pool Configuration

**User Story:** As a cloud infrastructure engineer, I want the Cognito User Pool configured for OTP-only authentication with the three required Lambda triggers, so that passwordless custom auth flows work correctly end-to-end.

#### Acceptance Criteria

1. THE Cognito User Pool SHALL support sign-in with both `email` and `phone_number` attributes
2. THE Cognito User Pool SHALL have password-based auth disabled; only `ALLOW_CUSTOM_AUTH` and `ALLOW_REFRESH_TOKEN_AUTH` flows SHALL be enabled on the App Client
3. THE Cognito User Pool SHALL have three Lambda triggers configured: `DefineAuthChallenge`, `CreateAuthChallenge`, and `VerifyAuthChallengeResponse`
4. THE Cognito Lambda trigger `CreateAuthChallenge` SHALL generate a 6-digit OTP, store it as a private challenge parameter, and deliver it via AWS SNS (for MOBILE) or AWS SES (for EMAIL)
5. THE Cognito Lambda trigger `VerifyAuthChallengeResponse` SHALL compare the user-supplied answer against the private challenge parameter and mark the challenge as satisfied only when they match
6. THE Cognito User Pool token validity SHALL be set to 60 minutes for Access Tokens, 60 minutes for ID Tokens, and 30 days for Refresh Tokens

### Requirement 17: API Documentation

**User Story:** As an API consumer, I want interactive OpenAPI documentation available at a well-known URL, so that I can explore and test the API without needing external documentation.

#### Acceptance Criteria

1. THE Auth_Service SHALL expose an OpenAPI 3.0 specification at `/v3/api-docs`
2. THE Auth_Service SHALL expose a Swagger UI at `/swagger-ui.html` with all six endpoints documented
3. THE Auth_Service SHALL configure the OpenAPI spec with HTTP Bearer JWT security scheme applied to protected endpoints
4. THE Auth_Service SHALL annotate all controller methods with `@Operation`, `@ApiResponses`, and `@ApiResponse` annotations specifying all success and error response schemas

### Requirement 18: Performance Targets

**User Story:** As a product owner, I want defined latency targets for the authentication APIs, so that we can validate the service meets user experience expectations under load.

#### Acceptance Criteria

1. THE Auth_Service SHALL achieve P99 latency below 500ms for `POST /api/v1/auth/login` at 100 RPS under normal Cognito response times
2. THE Auth_Service SHALL achieve P99 latency below 300ms for `POST /api/v1/auth/validate-otp` at 100 RPS under normal Cognito response times
3. THE Auth_Service SHALL configure the Cognito SDK HTTP client with appropriate `maxConcurrency` to match the expected TPS without connection starvation
