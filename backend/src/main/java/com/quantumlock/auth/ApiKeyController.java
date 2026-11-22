package com.quantumlock.auth;

import java.util.List;
import java.util.UUID;

import com.quantumlock.account.CurrentAccount;
import com.quantumlock.api.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lets a team manage its own API keys (task 3.3). Every call needs a valid key of that team. */
@RestController
@RequestMapping("/api/v1/api-keys")
@Tag(name = "API keys", description = "Create, list and revoke your team's API keys")
public class ApiKeyController {

    private final ApiKeyService apiKeys;
    private final CurrentAccount currentAccount;

    public ApiKeyController(ApiKeyService apiKeys, CurrentAccount currentAccount) {
        this.apiKeys = apiKeys;
        this.currentAccount = currentAccount;
    }

    @PostMapping
    @Operation(summary = "Create another API key for your team. The key is shown once, in this answer only.")
    public ResponseEntity<NewApiKeyResponse> create() {
        IssuedApiKey issued = apiKeys.issue(currentAccount.accountId(), "test");
        return ResponseEntity.status(HttpStatus.CREATED).body(NewApiKeyResponse.from(issued));
    }

    @GetMapping
    @Operation(summary = "List your team's API keys, newest first. Keys themselves are never shown.")
    public List<ApiKeyResponse> list() {
        return apiKeys.list(currentAccount.accountId()).stream().map(ApiKeyResponse::from).toList();
    }

    @DeleteMapping("/{apiKeyId}")
    @Operation(summary = "Revoke one of your team's API keys. It stops working immediately.")
    public ResponseEntity<Void> revoke(@PathVariable String apiKeyId) {
        apiKeys.revoke(currentAccount.accountId(), parseId(apiKeyId));
        return ResponseEntity.noContent().build();
    }

    /** Something that is not an id is answered like an unknown id. */
    private static UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "api_key_not_found");
        }
    }
}
