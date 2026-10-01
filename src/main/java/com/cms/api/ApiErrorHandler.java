package com.cms.api;

import com.cms.card.IssuanceException;
import com.cms.hsm.HsmException;
import com.cms.security.PanKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(IssuanceException.class)
    ResponseEntity<Map<String, String>> business(IssuanceException e) {
        HttpStatus status = switch (e.code()) {
            case "CARD_NOT_FOUND", "ACCOUNT_NOT_FOUND", "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "DUPLICATE" -> HttpStatus.CONFLICT;
            case "FOUR_EYES" -> HttpStatus.FORBIDDEN;
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

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Map<String, String>> signIn(AuthenticationException e) {
        String code = e instanceof LockedException ? "ACCOUNT_LOCKED"
                : e instanceof DisabledException ? "ACCOUNT_DISABLED" : "BAD_CREDENTIALS";
        String msg = switch (code) {
            case "ACCOUNT_LOCKED" -> "Account locked after too many failed attempts; ask an administrator";
            case "ACCOUNT_DISABLED" -> "Account disabled";
            default -> "Wrong username or password";
        };
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code", code, "message", msg));
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

    @ExceptionHandler(PanKeyException.class)
    ResponseEntity<Map<String, String>> panKey(PanKeyException e) {
        log.error("PAN key mismatch: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("code", "PAN_KEY_MISMATCH", "message", e.getMessage()));
    }

    /**
     * Anything not handled above: a JSON body with a reference that matches the log line, instead of
     * Spring's bare 500. Spring's own errors (403, 404, 405, ...) are passed on unchanged.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected(Exception e) throws Exception {
        if (e instanceof AccessDeniedException || e instanceof AuthenticationException || e instanceof ErrorResponse
                || AnnotationUtils.findAnnotation(e.getClass(), ResponseStatus.class) != null) {
            throw e;
        }
        String ref = UUID.randomUUID().toString().substring(0, 8);
        log.error("Unexpected error ref={}", ref, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", "INTERNAL_ERROR", "message", "Unexpected error; reference " + ref + " in the CMS log"));
    }
}
