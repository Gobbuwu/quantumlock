package com.quantumlock.health;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

import javax.sql.DataSource;

import com.quantumlock.crypto.CryptoProviders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simple readiness check: is the API running, can it reach PostgreSQL,
 * and is the Bouncy Castle crypto provider loaded? Public on purpose (no login),
 * so it answers only UP or DOWN.
 */
@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "Health", description = "Checks that the API, database and crypto provider are ready")
public class HealthController {

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping
    @Operation(summary = "Report whether the API, database and crypto provider are ready")
    @SecurityRequirements // none: public, so Swagger UI shows no lock (KAN-41)
    public ResponseEntity<HealthResponse> health() {
        boolean databaseUp = isDatabaseUp();
        boolean cryptoUp = CryptoProviders.isRegistered();
        boolean ready = databaseUp && cryptoUp;

        HealthResponse body = new HealthResponse(
                ready ? "UP" : "DEGRADED",
                databaseUp ? "UP" : "DOWN",
                cryptoUp ? "UP" : "DOWN",
                Instant.now().toString());

        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private boolean isDatabaseUp() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (SQLException e) {
            // Deliberately not returned to the caller: driver messages can contain host names and user names.
            return false;
        }
    }
}
