package com.quantumlock.crypto;

import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * Registers the Bouncy Castle provider ("BC") with the JVM, which is what makes
 * ML-KEM and ML-DSA available through the standard Java crypto classes
 * (KeyPairGenerator, KeyGenerator, Signature). Safe to call more than once.
 */
public final class CryptoProviders {

    public static final String BC = BouncyCastleProvider.PROVIDER_NAME;

    private CryptoProviders() {
    }

    public static synchronized void register() {
        if (Security.getProvider(BC) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    /** True when the Bouncy Castle provider is registered with the JVM. */
    public static boolean isRegistered() {
        return Security.getProvider(BC) != null;
    }
}
