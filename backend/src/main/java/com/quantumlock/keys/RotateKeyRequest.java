package com.quantumlock.keys;

import io.swagger.v3.oas.annotations.media.Schema;

/** Body of POST /api/v1/keys/{keyPairId}/rotate. The body itself is optional. */
public record RotateKeyRequest(
        @Schema(description = "Days the old key pair can still decapsulate and verify. Leave out for the default "
                + "(7 unless ROTATION_GRACE_DAYS changes it). 0 ends its use at once.", example = "7",
                minimum = "0")
        Integer gracePeriodDays) {
}
