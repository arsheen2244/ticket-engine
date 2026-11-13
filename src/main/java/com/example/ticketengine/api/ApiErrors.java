package com.example.ticketengine.api;

import com.example.ticketengine.LimitExceededException;
import com.example.ticketengine.SoldOutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Error bodies for the endpoints added for the web app: always {"error": "..."} so the frontend can
 * show the message. Limited to these packages, so the original API's responses are unchanged.
 */
@RestControllerAdvice(basePackages = {"com.example.ticketengine.auth", "com.example.ticketengine.api",
        "com.example.ticketengine.live"})
public class ApiErrors {

    public static final String SOLD_OUT = "Sold out";

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> status(ResponseStatusException e) {
        String reason = e.getReason() != null ? e.getReason() : HttpStatus.valueOf(e.getStatusCode().value()).getReasonPhrase();
        return body(HttpStatus.valueOf(e.getStatusCode().value()), reason);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .sorted().collect(Collectors.joining("; "));
        return body(HttpStatus.BAD_REQUEST, msg.isEmpty() ? "Invalid request" : msg);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<Map<String, String>> missingHeader(MissingRequestHeaderException e) {
        return body(HttpStatus.BAD_REQUEST, "Missing header " + e.getHeaderName());
    }

    @ExceptionHandler(SoldOutException.class)
    ResponseEntity<Map<String, String>> soldOut() { return body(HttpStatus.CONFLICT, SOLD_OUT); }

    @ExceptionHandler(LimitExceededException.class)
    ResponseEntity<Map<String, String>> limit(LimitExceededException e) {
        return body(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
