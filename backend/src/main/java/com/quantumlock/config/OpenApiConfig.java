package com.quantumlock.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Title and description shown at the top of the Swagger UI page.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI quantumLockOpenApi() {
        return new OpenAPI().info(new Info()
                .title("QuantumLock API")
                .version("0.1.0")
                .description("NIST post-quantum key generation and key exchange (ML-KEM-1024, ML-DSA-65) "
                        + "for small development teams. Capstone prototype, group 269_09."));
    }
}
