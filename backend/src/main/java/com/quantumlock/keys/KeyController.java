package com.quantumlock.keys;

import java.util.List;
import java.util.UUID;

import com.quantumlock.account.CurrentAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Key pair endpoints (task 3.2). */
@RestController
@RequestMapping("/api/v1/keys")
@Tag(name = "Keys", description = "Generate, list, rotate and revoke post-quantum key pairs")
public class KeyController {

    private final KeyService keys;
    private final CurrentAccount currentAccount;

    public KeyController(KeyService keys, CurrentAccount currentAccount) {
        this.keys = keys;
        this.currentAccount = currentAccount;
    }

    @PostMapping("/generate")
    @Operation(summary = "Generate a key pair. The private key is stored encrypted and is never returned.")
    public ResponseEntity<KeyPairResponse> generate(@Valid @RequestBody GenerateKeyRequest request) {
        KeyPairEntity created =
                keys.generate(currentAccount.accountId(), currentAccount.apiKeyId(), request.algorithm());
        return ResponseEntity.status(HttpStatus.CREATED).body(KeyPairResponse.from(created));
    }

    @GetMapping
    @Operation(summary = "List the account's key pairs, newest first")
    public List<KeyPairResponse> list() {
        return keys.list(currentAccount.accountId()).stream().map(KeyPairResponse::from).toList();
    }

    @GetMapping("/{keyPairId}")
    @Operation(summary = "Get one key pair of the account")
    public KeyPairResponse get(@PathVariable UUID keyPairId) {
        return KeyPairResponse.from(keys.get(currentAccount.accountId(), keyPairId));
    }

    @PostMapping("/{keyPairId}/rotate")
    @Operation(summary = "Replace an active key pair with a new one of the same algorithm. The old one can still "
            + "decapsulate and verify during the grace period. Returns the new key pair.")
    public ResponseEntity<KeyPairResponse> rotate(@PathVariable UUID keyPairId,
            @RequestBody(required = false) RotateKeyRequest request) {
        KeyPairEntity replacement = keys.rotate(currentAccount.accountId(), currentAccount.apiKeyId(), keyPairId,
                request == null ? null : request.gracePeriodDays());
        return ResponseEntity.status(HttpStatus.CREATED).body(KeyPairResponse.from(replacement));
    }

    @PostMapping("/{keyPairId}/revoke")
    @Operation(summary = "Stop a key pair for good. It cannot be undone.")
    public KeyPairResponse revoke(@PathVariable UUID keyPairId) {
        return KeyPairResponse.from(keys.revoke(currentAccount.accountId(), currentAccount.apiKeyId(), keyPairId));
    }
}
