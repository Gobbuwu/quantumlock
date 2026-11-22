package com.quantumlock.client;

/**
 * Base class of every error the client throws for a failed call. Messages are fixed text written in
 * this SDK: they never contain the API key, a secret or text from the server's response.
 */
public class QuantumLockException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    QuantumLockException(String message) {
        super(message);
    }

    QuantumLockException(String message, Throwable cause) {
        super(message, cause);
    }
}
