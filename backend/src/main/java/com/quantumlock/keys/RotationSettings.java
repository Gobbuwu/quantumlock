package com.quantumlock.keys;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Grace periods for key rotation (KAN-30), in whole days. Set in backend/.env:
 * ROTATION_GRACE_DAYS (default 7) and ROTATION_MAX_GRACE_DAYS (default 90). The application refuses to start
 * with values that do not fit together.
 */
@Component
class RotationSettings {

    /** About ten years: a longer grace period would amount to never retiring the old key. */
    static final int LARGEST_MAX_DAYS = 3650;

    private final int defaultDays;
    private final int maxDays;

    RotationSettings(@Value("${quantumlock.rotation.default-grace-period-days:7}") int defaultDays,
            @Value("${quantumlock.rotation.max-grace-period-days:90}") int maxDays) {
        if (maxDays < 0 || maxDays > LARGEST_MAX_DAYS) {
            throw new IllegalStateException("ROTATION_MAX_GRACE_DAYS must be 0 to " + LARGEST_MAX_DAYS + " days.");
        }
        if (defaultDays < 0 || defaultDays > maxDays) {
            throw new IllegalStateException("ROTATION_GRACE_DAYS must be 0 to ROTATION_MAX_GRACE_DAYS (" + maxDays
                    + ") days.");
        }
        this.defaultDays = defaultDays;
        this.maxDays = maxDays;
    }

    int defaultDays() {
        return defaultDays;
    }

    int maxDays() {
        return maxDays;
    }
}
