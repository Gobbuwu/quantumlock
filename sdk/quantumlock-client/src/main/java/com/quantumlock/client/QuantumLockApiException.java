package com.quantumlock.client;

import java.util.regex.Pattern;

/**
 * The API answered with an error, for example 401 "unauthorized" or 404 "key_not_found". The error codes
 * are listed in the README. Only a code of lower-case letters and underscores is taken from the response;
 * anything else is reported as "unknown_error", so server text never reaches a log through this exception.
 */
public class QuantumLockApiException extends QuantumLockException {

    private static final long serialVersionUID = 1L;
    private static final Pattern CODE = Pattern.compile("[a-z_]{1,64}");

    /** Code used when the response carries no readable error code. */
    public static final String UNKNOWN_ERROR = "unknown_error";

    private final int status;
    private final String errorCode;

    QuantumLockApiException(int status, String rawCode) {
        super("QuantumLock API error " + status + " " + safe(rawCode));
        this.status = status;
        this.errorCode = safe(rawCode);
    }

    /** The HTTP status, for example 404. */
    public int status() {
        return status;
    }

    /** The API's error code, for example "key_not_found", or {@value #UNKNOWN_ERROR}. */
    public String errorCode() {
        return errorCode;
    }

    private static String safe(String code) {
        return code != null && CODE.matcher(code).matches() ? code : UNKNOWN_ERROR;
    }
}
