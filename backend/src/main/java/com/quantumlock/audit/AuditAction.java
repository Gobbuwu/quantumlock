package com.quantumlock.audit;

/** The actions recorded in audit_logs.action. */
public enum AuditAction {

    GENERATE("generate"),
    ENCAPSULATE("encapsulate"),
    DECAPSULATE("decapsulate"),
    SIGN("sign"),
    VERIFY("verify"),
    ROTATE("rotate"),
    REVOKE("revoke");

    private final String value;

    AuditAction(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
