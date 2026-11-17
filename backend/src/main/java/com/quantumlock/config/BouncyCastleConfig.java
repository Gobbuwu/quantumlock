package com.quantumlock.config;

import com.quantumlock.crypto.CryptoProviders;
import org.springframework.context.annotation.Configuration;

/**
 * Registers Bouncy Castle when the application context starts,
 * both when the app runs and in @SpringBootTest tests.
 */
@Configuration
public class BouncyCastleConfig {

    public BouncyCastleConfig() {
        CryptoProviders.register();
    }
}
