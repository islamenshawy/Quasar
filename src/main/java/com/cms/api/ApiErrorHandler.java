package com.cms.api;

import com.cms.card.IssuanceException;
import com.cms.hsm.HsmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(IssuanceException.class)
    ResponseEntity<Map<String, String>> business(IssuanceException e) {
        HttpStatus status = switch (e.code()) {
            case "CARD_NOT_FOUND", "ACCOUNT_NOT_FOUND", "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "DUPLICATE" -> HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(Map.of("code", e.code(), "message", e.getMessage()));
    }

    /** Unique constraint hit by a concurrent request that passed the service's own duplicate check. */
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<Map<String, String>> duplicate(DuplicateKeyException e) {
        log.warn("Duplicate key: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "DUPLICATE", "message", "Record already exists"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "INVALID_REQUEST", "message", "Malformed request body"));
    }

    @ExceptionHandler(EmptyResultDataAccessException.class)
    ResponseEntity<Map<String, String>> notFound(EmptyResultDataAccessException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "NOT_FOUND", "message", "Record not found"));
    }

    @ExceptionHandler(HsmException.class)
    ResponseEntity<Map<String, String>> hsm(HsmException e) {
        log.error("HSM error cmd={} code={}", e.command(), e.errorCode(), e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("code", "HSM_ERROR", "message", "Security module error " + e.command()
                        + (e.errorCode() == null ? "" : " / " + e.errorCode())));
    }
}
