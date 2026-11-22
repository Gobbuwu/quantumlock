package com.quantumlock.client;

/** The algorithms the QuantumLock API offers. */
public enum Algorithm {

    /** ML-KEM-1024 (FIPS 203): key exchange. Supports encapsulate and decapsulate. */
    ML_KEM_1024("ML-KEM-1024"),
    /** ML-DSA-65 (FIPS 204): signatures. */
    ML_DSA_65("ML-DSA-65");

    private final String apiName;

    Algorithm(String apiName) {
        this.apiName = apiName;
    }

    /** The name the API uses, for example "ML-KEM-1024". */
    public String apiName() {
        return apiName;
    }

    static Algorithm fromApiName(String name) {
        for (Algorithm algorithm : values()) {
            if (algorithm.apiName.equals(name)) {
                return algorithm;
            }
        }
        throw new QuantumLockResponseException("unknown algorithm in the response");
    }
}
