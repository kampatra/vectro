package com.example.auth.exception;

import org.springframework.http.HttpStatus;

/**
 * Wraps Cognito SDK errors with an HTTP status and a short error code.
 */
public class CognitoAuthException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public CognitoAuthException(String errorCode, String message, HttpStatus status) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public HttpStatus getStatus() { return status; }
    public String getErrorCode()  { return errorCode; }
}
