package com.quantumlock.keys;

import java.util.Optional;

/** The algorithms QuantumLock offers. Anything else is refused. */
public enum Algorithm {

    ML_KEM_1024("ML-KEM-1024"),
    ML_DSA_65("ML-DSA-65");

    private final String displayName;

    Algorithm(String displayName) {
        this.displayName = displayName;
    }

    /** The name used in the API and in the database, for example "ML-KEM-1024". */
    public String displayName() {
        return displayName;
    }

    /** Exact, case-sensitive match on the display name. */
    public static Optional<Algorithm> fromDisplayName(String name) {
        for (Algorithm algorithm : values()) {
            if (algorithm.displayName.equals(name)) {
                return Optional.of(algorithm);
            }
        }
        return Optional.empty();
    }
}
