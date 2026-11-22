package com.quantumlock.auth;

import java.util.UUID;

import com.quantumlock.account.CurrentAccount;
import com.quantumlock.api.ApiException;
import com.quantumlock.security.ApiKeyAuthFilter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The account of the API key that {@link ApiKeyAuthFilter} accepted for the current request.
 * If no key was accepted (which the filter should already have prevented) the request is refused
 * with the same 401, never answered for some default account.
 */
@Component
public class RequestCurrentAccount implements CurrentAccount {

    @Override
    public UUID accountId() {
        return caller().accountId();
    }

    @Override
    public UUID apiKeyId() {
        return caller().apiKeyId();
    }

    private static Caller caller() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        Object caller = attributes == null
                ? null
                : attributes.getAttribute(ApiKeyAuthFilter.CALLER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (caller instanceof Caller c) {
            return c;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized");
    }
}
