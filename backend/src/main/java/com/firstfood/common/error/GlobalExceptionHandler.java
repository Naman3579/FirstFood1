package com.firstfood.common.error;

import com.firstfood.common.web.RequestBodyTooLargeException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Centralized error handling (phases.md Phase 1, architecture.md #30).
 *
 * Returns stable, machine-readable error codes rather than leaking stack
 * traces or framework-specific messages to clients (rules.md Rule 18).
 * Module-specific business exceptions should extend
 * {@link DomainException} and get mapped here rather than each controller
 * inventing its own error shape.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(
                "VALIDATION_ERROR",
                "Request failed validation."
        ));
    }

    /** Malformed JSON, or a value that isn't a valid enum constant. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        if (isBodyTooLarge(ex)) {
            return ResponseEntity.status(HttpStatus.valueOf(413)).body(errorBody(
                    "PAYLOAD_TOO_LARGE",
                    "Request body is too large."
            ));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(
                "VALIDATION_ERROR",
                "Request body is malformed or contains an invalid value."
        ));
    }

    /** E.g. a path variable that should be a UUID but isn't. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(
                "VALIDATION_ERROR",
                "Request contains an invalid parameter."
        ));
    }

    /** No such route (e.g. an endpoint that deliberately does not exist for this caller): 404, never a 500. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoRoute(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody("NOT_FOUND", "Resource not found."));
    }

    /** The route exists but not for this HTTP method. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(errorBody(
                "METHOD_NOT_ALLOWED", "This method is not supported for this resource."));
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<Map<String, Object>> handleDomain(DomainException ex) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.getStatus());
        if (ex instanceof RetryAfterAware retryAfter) {
            response.header("Retry-After", Long.toString(retryAfter.retryAfterSeconds()));
        }
        return response.body(errorBody(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        // Previously swallowed silently, which made every 500 invisible. Log the type and the top of the stack, but
        // NOT the message: persistence/validation messages can embed the offending values (phone numbers, emails).
        log.error("Unhandled exception type={} at={}", ex.getClass().getName(),
                Arrays.stream(ex.getStackTrace()).limit(8).map(StackTraceElement::toString).toList());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody(
                "INTERNAL_ERROR",
                "Something went wrong."
        ));
    }

    private Map<String, Object> errorBody(String code, String message) {
        return Map.of(
                "code", code,
                "message", message,
                "timestamp", Instant.now().toString()
        );
    }

    private static boolean isBodyTooLarge(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof RequestBodyTooLargeException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
