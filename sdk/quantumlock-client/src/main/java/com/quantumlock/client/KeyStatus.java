package com.quantumlock.client;

/** Status of a key pair. Only an active key pair can be used for encapsulate and decapsulate. */
public enum KeyStatus {

    ACTIVE("active"),
    ROTATED("rotated"),
    REVOKED("revoked");

    private final String apiName;

    KeyStatus(String apiName) {
        this.apiName = apiName;
    }

    /** The name the API uses, for example "active". */
    public String apiName() {
        return apiName;
    }

    static KeyStatus fromApiName(String name) {
        for (KeyStatus status : values()) {
            if (status.apiName.equals(name)) {
                return status;
            }
        }
        throw new QuantumLockResponseException("unknown key status in the response");
    }
}
