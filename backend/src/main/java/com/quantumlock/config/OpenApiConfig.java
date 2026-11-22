package com.quantumlock.config;

import com.quantumlock.auth.ApiKeys;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Title and description shown at the top of the Swagger UI page, and the API-key header, so the
 * "Authorize" button in Swagger UI can send it.
 */
@Configuration
public class OpenApiConfig {

    private static final String API_KEY_SCHEME = "apiKey";

    @Bean
    public OpenAPI quantumLockOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("QuantumLock API")
                        .version("0.1.0")
                        .description("NIST post-quantum key generation and key exchange (ML-KEM-1024, ML-DSA-65) "
                                + "for small development teams. Capstone prototype, group 269_09. "
                                + "Every endpoint except the health check needs an API key in the "
                                + ApiKeys.HEADER + " header."))
                .components(new Components().addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name(ApiKeys.HEADER)))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
    }
}
