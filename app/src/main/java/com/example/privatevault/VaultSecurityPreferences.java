package com.example.privatevault;

import android.content.SharedPreferences;

import java.util.Objects;

/** Typed access to security and category-depth preferences. */
final class VaultSecurityPreferences {
    static final long AUTO_LOCK_IMMEDIATELY = 0L;
    static final int HARD_MAX_CATEGORY_DEPTH = 5;

    private static final String PREF_CLIPBOARD_TIMEOUT_MS = "clipboard_timeout_ms_v1";
    private static final String PREF_AUTO_LOCK_MS = "auto_lock_ms_v1";
    private static final String PREF_LOCK_ON_SCREEN_OFF = "lock_on_screen_off_v1";
    private static final String PREF_MAX_CATEGORY_DEPTH = "max_category_depth_v1";
    private static final long DEFAULT_CLIPBOARD_TIMEOUT_MS = ClipboardSecurityManager.THIRTY_SECONDS;
    private static final long DEFAULT_AUTO_LOCK_MS = 30_000L;
    private static final int DEFAULT_MAX_CATEGORY_DEPTH = 3;

    private final SharedPreferences preferences;

    VaultSecurityPreferences(SharedPreferences preferences) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
    }

    long autoLockMs() {
        return preferences.getLong(PREF_AUTO_LOCK_MS, DEFAULT_AUTO_LOCK_MS);
    }

    void setAutoLockMs(long value) {
        preferences.edit().putLong(PREF_AUTO_LOCK_MS, value).apply();
    }

    boolean lockOnScreenOff() {
        return preferences.getBoolean(PREF_LOCK_ON_SCREEN_OFF, true);
    }

    void setLockOnScreenOff(boolean value) {
        preferences.edit().putBoolean(PREF_LOCK_ON_SCREEN_OFF, value).apply();
    }

    long clipboardTimeoutMs() {
        return preferences.getLong(PREF_CLIPBOARD_TIMEOUT_MS, DEFAULT_CLIPBOARD_TIMEOUT_MS);
    }

    void setClipboardTimeoutMs(long value) {
        preferences.edit().putLong(PREF_CLIPBOARD_TIMEOUT_MS, value).apply();
    }

    int maxCategoryDepth() {
        int configured = preferences.getInt(PREF_MAX_CATEGORY_DEPTH, DEFAULT_MAX_CATEGORY_DEPTH);
        return Math.max(1, Math.min(HARD_MAX_CATEGORY_DEPTH, configured));
    }

    void setMaxCategoryDepth(int value) {
        preferences.edit().putInt(PREF_MAX_CATEGORY_DEPTH, value).apply();
    }
}
