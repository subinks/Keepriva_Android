package com.example.privatevault;

import java.util.Objects;

import javax.crypto.SecretKey;

/**
 * Sole in-memory owner of the active vault key.
 *
 * The coordinator deliberately exposes no Android persistence contract. The key
 * must never be placed in Bundle, Parcelable, Serializable, preferences, or UI
 * state. Clearing drops the only session-owned reference.
 */
final class VaultSessionCoordinator {
    private SecretKey key;

    synchronized void unlock(SecretKey unlockedKey) {
        key = Objects.requireNonNull(unlockedKey, "unlockedKey");
    }

    synchronized boolean isUnlocked() {
        return key != null;
    }

    synchronized SecretKey requireKey() {
        if (key == null) {
            throw new IllegalStateException("Vault session is locked");
        }
        return key;
    }

    synchronized void clear() {
        key = null;
    }
}

