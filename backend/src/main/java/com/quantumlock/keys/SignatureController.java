package com.quantumlock.keys;

import java.util.Base64;
import java.util.UUID;

import com.quantumlock.account.CurrentAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ML-DSA-65 signature endpoints (KAN-29). */
@RestController
@RequestMapping("/api/v1/keys")
@Tag(name = "Signatures", description = "Sign data and check signatures with ML-DSA-65 (FIPS 204)")
public class SignatureController {

    private final SignatureService signatures;
    private final CurrentAccount currentAccount;

    public SignatureController(SignatureService signatures, CurrentAccount currentAccount) {
        this.signatures = signatures;
        this.currentAccount = currentAccount;
    }

    @PostMapping("/{keyPairId}/sign")
    @Operation(summary = "Sign data with the private key of one of your ML-DSA-65 key pairs")
    public SignResponse sign(@PathVariable UUID keyPairId, @Valid @RequestBody SignRequest request) {
        byte[] signature = signatures.sign(currentAccount.accountId(), currentAccount.apiKeyId(), keyPairId,
                request.message(), request.context());
        return new SignResponse(keyPairId.toString(), Base64.getEncoder().encodeToString(signature));
    }

    @PostMapping("/{keyPairId}/verify")
    @Operation(summary = "Check a signature with the public key of one of your ML-DSA-65 key pairs")
    public VerifyResponse verify(@PathVariable UUID keyPairId, @Valid @RequestBody VerifyRequest request) {
        boolean valid = signatures.verify(currentAccount.accountId(), currentAccount.apiKeyId(), keyPairId,
                request.message(), request.signature(), request.context());
        return new VerifyResponse(keyPairId.toString(), valid);
    }
}
