package com.quantumlock.client;

import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * The API answered with an error, for example 401 "unauthorized" or 404 "key_not_found". The error codes
 * are listed in the README. Only a code of lower-case letters and underscores is taken from the response;
 * anything else is reported as "unknown_error", so server text never reaches a log through this exception.
 */
public class QuantumLockApiException extends QuantumLockException {

    private static final long serialVersionUID = 1L;
    private static final Pattern CODE = Pattern.compile("[a-z_]{1,64}");
    private static final Pattern SECONDS = Pattern.compile("[0-9]{1,6}");

    /** Code used when the response carries no readable error code. */
    public static final String UNKNOWN_ERROR = "unknown_error";

    private final int status;
    private final String errorCode;
    private final long retryAfterSeconds;

    QuantumLockApiException(int status, String rawCode) {
        this(status, rawCode, null);
    }

    QuantumLockApiException(int status, String rawCode, String rawRetryAfter) {
        super("QuantumLock API error " + status + " " + safe(rawCode));
        this.status = status;
        this.errorCode = safe(rawCode);
        this.retryAfterSeconds = rawRetryAfter != null && SECONDS.matcher(rawRetryAfter).matches()
                ? Long.parseLong(rawRetryAfter) : -1;
    }

    /** The HTTP status, for example 404. */
    public int status() {
        return status;
    }

    /** The API's error code, for example "key_not_found", or {@value #UNKNOWN_ERROR}. */
    public String errorCode() {
        return errorCode;
    }

    /**
     * How many seconds to wait before trying again, from the Retry-After header. The API sends it with 429
     * "rate_limited". Empty when the header is missing or is not a whole number of seconds.
     */
    public OptionalLong retryAfterSeconds() {
        return retryAfterSeconds < 0 ? OptionalLong.empty() : OptionalLong.of(retryAfterSeconds);
    }

    private static String safe(String code) {
        return code != null && CODE.matcher(code).matches() ? code : UNKNOWN_ERROR;
    }
}
