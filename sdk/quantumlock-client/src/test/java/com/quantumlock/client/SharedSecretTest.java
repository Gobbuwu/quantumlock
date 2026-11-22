package com.quantumlock.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SharedSecretTest {

    @Test
    void copiesAreIndependent() {
        SharedSecret secret = new SharedSecret(filled(5));
        byte[] copy = secret.copyBytes();
        copy[0] = 99;
        assertThat(secret.copyBytes()[0]).isEqualTo((byte) 5);
    }

    @Test
    void destroyWipesTheBytesAndBlocksFurtherUse() {
        byte[] held = filled(5);
        SharedSecret secret = new SharedSecret(held);

        secret.destroy();

        assertThat(held).containsOnly(0);
        assertThat(secret.isDestroyed()).isTrue();
        assertThat(secret.toString()).isEqualTo("SharedSecret[destroyed]");
        assertThatThrownBy(secret::copyBytes).isInstanceOf(IllegalStateException.class);
        secret.destroy(); // a second call does nothing
    }

    @Test
    void closeIsDestroy() {
        SharedSecret secret = new SharedSecret(filled(5));
        try (secret) {
            assertThat(secret.isDestroyed()).isFalse();
        }
        assertThat(secret.isDestroyed()).isTrue();
    }

    @Test
    void matchesComparesTheBytes() {
        assertThat(new SharedSecret(filled(5)).matches(new SharedSecret(filled(5)))).isTrue();
        assertThat(new SharedSecret(filled(5)).matches(new SharedSecret(filled(6)))).isFalse();
        SharedSecret destroyed = new SharedSecret(filled(5));
        destroyed.destroy();
        assertThatThrownBy(() -> new SharedSecret(filled(5)).matches(destroyed)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void equalsIsIdentityOnly() {
        SharedSecret a = new SharedSecret(filled(5));
        assertThat(a).isEqualTo(a).isNotEqualTo(new SharedSecret(filled(5)));
    }

    @Test
    void onlyThirtyTwoBytesAreAccepted() {
        assertThatThrownBy(() -> new SharedSecret(new byte[31])).isInstanceOf(QuantumLockResponseException.class);
        assertThatThrownBy(() -> new SharedSecret(new byte[33])).isInstanceOf(QuantumLockResponseException.class);
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[SharedSecret.LENGTH];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
