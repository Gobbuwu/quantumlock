package com.quantumlock.client;

/**
 * The API answered, but not with what QuantumLock sends: an unexpected status (a redirect, for example),
 * a body that is too large or not valid JSON, a missing field or a value of the wrong length. It never
 * carries the response body or a parser error, because a malformed body could still contain a secret.
 */
public class QuantumLockResponseException extends QuantumLockException {

    private static final long serialVersionUID = 1L;

    QuantumLockResponseException(String message) {
        super(message);
    }
}
