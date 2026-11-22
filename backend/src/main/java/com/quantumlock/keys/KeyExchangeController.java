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

/** ML-KEM-1024 key exchange endpoints (task 3.4). */
@RestController
@RequestMapping("/api/v1/keys")
@Tag(name = "Key exchange", description = "Agree on a shared secret with ML-KEM-1024 (FIPS 203)")
public class KeyExchangeController {

    private final KeyExchangeService keyExchange;
    private final CurrentAccount currentAccount;

    public KeyExchangeController(KeyExchangeService keyExchange, CurrentAccount currentAccount) {
        this.keyExchange = keyExchange;
        this.currentAccount = currentAccount;
    }

    @PostMapping("/{keyPairId}/encapsulate")
    @Operation(summary = "Make a new shared secret for one of your ML-KEM-1024 key pairs, and its ciphertext")
    public EncapsulateResponse encapsulate(@PathVariable UUID keyPairId) {
        KeyExchangeService.Encapsulation result =
                keyExchange.encapsulate(currentAccount.accountId(), currentAccount.apiKeyId(), keyPairId);
        return new EncapsulateResponse(keyPairId.toString(), base64(result.ciphertext()),
                base64(result.sharedSecret()));
    }

    @PostMapping("/{keyPairId}/decapsulate")
    @Operation(summary = "Get the shared secret out of a ciphertext, with the private key of one of your key pairs")
    public DecapsulateResponse decapsulate(@PathVariable UUID keyPairId,
            @Valid @RequestBody DecapsulateRequest request) {
        byte[] sharedSecret = keyExchange.decapsulate(currentAccount.accountId(), currentAccount.apiKeyId(),
                keyPairId, request.ciphertext());
        return new DecapsulateResponse(keyPairId.toString(), base64(sharedSecret));
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
