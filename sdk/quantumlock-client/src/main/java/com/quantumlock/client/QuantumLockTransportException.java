package com.quantumlock.client;

/** The API could not be reached, the call timed out or the thread was interrupted. Retrying may help. */
public class QuantumLockTransportException extends QuantumLockException {

    private static final long serialVersionUID = 1L;

    QuantumLockTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
