package com.quantumlock.health;

/**
 * Body returned by GET /api/v1/health.
 *
 * <p>This endpoint needs no login, so it only reports UP or DOWN. It never returns
 * library versions, host names or error details, which would help an attacker.
 *
 * @param status         "UP" when everything works, "DEGRADED" otherwise
 * @param database       "UP" or "DOWN"
 * @param cryptoProvider "UP" when Bouncy Castle is registered, otherwise "DOWN"
 * @param checkedAt      time of the check (ISO-8601, UTC)
 */
public record HealthResponse(String status, String database, String cryptoProvider, String checkedAt) {
}
