package com.example.ticketengine;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class LimitExceededException extends RuntimeException {
    public LimitExceededException() { super("Per-user ticket limit exceeded"); }
}
