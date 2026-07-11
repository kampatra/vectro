package com.example.auth.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class ErrorResponse {
    private String errorCode;
    private String message;
    private Instant timestamp;
    private String path;
}
