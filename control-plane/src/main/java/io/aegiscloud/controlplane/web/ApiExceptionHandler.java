package io.aegiscloud.controlplane.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Renders every failure as the same {@code {"error","message"}} envelope the
 * dashboard's API client already parses.
 *
 * <p>Unexpected exceptions are logged in full but answered with a generic message:
 * a SQL error text reaching the browser would leak schema detail to anyone holding
 * a viewer token.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handleApi(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of(
                "error", e.code(),
                "message", e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "BAD_REQUEST",
                "message", "invalid request body"));
    }

    /**
     * Turns a denied {@code @PreAuthorize} check into 403.
     *
     * <p>Method security raises this inside the dispatcher, so it reaches this
     * advice rather than the filter chain's AccessDeniedHandler — which only sees
     * exceptions thrown by filters. Without this mapping the catch-all below would
     * answer an authorisation failure with 500, telling a caller the server broke
     * when in fact the rule worked exactly as intended.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", "FORBIDDEN",
                "message", "insufficient role"));
    }

    /** An unauthenticated caller reaching a secured method, for the same reason. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, String>> handleUnauthenticated(AuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "error", "UNAUTHORIZED",
                "message", "missing bearer token"));
    }

    /**
     * A request for a path that does not exist is 404, not 500.
     *
     * <p>Spring raises NoResourceFoundException for these, and without this mapping
     * the catch-all below reported a mistyped URL - or a browser asking for
     * favicon.ico - as a server failure, which is both wrong and alarming on a
     * platform whose subject is reliability.
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleMissingRoute(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "NOT_FOUND",
                "message", "no such endpoint: " + e.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("request failed on {} {}", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "INTERNAL",
                "message", "could not load data"));
    }
}
