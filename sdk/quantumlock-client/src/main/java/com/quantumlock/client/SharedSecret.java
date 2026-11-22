package com.quantumlock.client;

import java.security.MessageDigest;
import java.util.Arrays;

import javax.security.auth.Destroyable;

/**
 * A 32-byte ML-KEM shared secret (FIPS 203 K). Use it as a key, for example for AES-256-GCM, and
 * {@link #destroy()} it (or use try-with-resources) as soon as it is no longer needed: that overwrites
 * the bytes held here with zeros.
 *
 * <p>It never shows its value: {@link #toString()} prints "hidden", so it cannot end up in a log by
 * accident. {@link #equals(Object)} is identity only; use {@link #matches(SharedSecret)} to compare two
 * secrets in constant time.
 */
public final class SharedSecret implements Destroyable, AutoCloseable {

    /** Length of an ML-KEM shared secret in bytes. */
    public static final int LENGTH = 32;

    private final byte[] bytes;
    private volatile boolean destroyed;

    /** Takes ownership of {@code bytes}: the caller must not keep or reuse the array. */
    SharedSecret(byte[] bytes) {
        if (bytes.length != LENGTH) {
            throw new QuantumLockResponseException("shared secret has the wrong length");
        }
        this.bytes = bytes;
    }

    /** A copy of the 32 bytes. The caller is responsible for wiping the copy. */
    public byte[] copyBytes() {
        checkNotDestroyed();
        return bytes.clone();
    }

    /** True when both secrets hold the same bytes. Takes the same time whatever the bytes are. */
    public boolean matches(SharedSecret other) {
        checkNotDestroyed();
        other.checkNotDestroyed();
        return MessageDigest.isEqual(bytes, other.bytes);
    }

    /** Overwrites the bytes with zeros. Calling it again does nothing. */
    @Override
    public void destroy() {
        Arrays.fill(bytes, (byte) 0);
        destroyed = true;
    }

    @Override
    public boolean isDestroyed() {
        return destroyed;
    }

    /** Same as {@link #destroy()}. */
    @Override
    public void close() {
        destroy();
    }

    @Override
    public String toString() {
        return destroyed ? "SharedSecret[destroyed]" : "SharedSecret[hidden]";
    }

    private void checkNotDestroyed() {
        if (destroyed) {
            throw new IllegalStateException("The shared secret has been destroyed");
        }
    }
}
