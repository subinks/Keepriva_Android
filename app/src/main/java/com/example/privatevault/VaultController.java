package com.example.privatevault;

/**
 * Session-scoped UI owner that must release every Activity, View, Dialog,
 * callback, and decrypted presentation reference when the vault is locked.
 */
interface VaultController {
    /** Must be safe to call more than once. */
    void close();
}

