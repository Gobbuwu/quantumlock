package com.quantumlock.api;

import org.springframework.http.HttpStatus;

/**
 * An error the API reports to the caller as {"error": "<code>"} with the given status.
 * The code is a fixed word chosen here, never text taken from the request or from a library.
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
