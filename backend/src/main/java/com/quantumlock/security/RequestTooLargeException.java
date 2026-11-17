package com.quantumlock.security;

import java.io.IOException;

/** Thrown while reading a request body that turns out to be larger than the configured limit. */
public class RequestTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    public RequestTooLargeException(long maxBytes) {
        super("Request body is larger than the limit of " + maxBytes + " bytes");
    }
}
