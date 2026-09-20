package com.example.privatevault;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;

/**
 * Handles sensitive clipboard writes and best-effort timed cleanup.
 *
 * The delayed cleanup only clears the clipboard if it still contains the exact
 * sensitive value written by this app. This avoids erasing a newer clipboard
 * value that the user copied from another app after copying a password.
 */
public final class ClipboardSecurityManager {
    public static final long NEVER_CLEAR = 0L;
    public static final long FIFTEEN_SECONDS = 15_000L;
    public static final long THIRTY_SECONDS = 30_000L;
    public static final long SIXTY_SECONDS = 60_000L;

    private final ClipboardManager clipboard;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingClear;
    private String lastSensitiveValue;

    public ClipboardSecurityManager(Context context) {
        clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    public void copySensitive(String label, String value, long clearAfterMs) {
        if (value == null || value.isEmpty()) return;
        cancelPendingClear();

        ClipData clip = ClipData.newPlainText(label == null ? "Sensitive value" : label, value);
        markSensitive(clip.getDescription());
        clipboard.setPrimaryClip(clip);
        lastSensitiveValue = value;

        if (clearAfterMs > 0) {
            pendingClear = () -> clearIfStillOwnedValue(value);
            handler.postDelayed(pendingClear, clearAfterMs);
        }
    }

    /** Clears only the sensitive value most recently copied by this app. */
    public void clearSensitiveClipboardNow() {
        cancelPendingClear();
        if (lastSensitiveValue != null) clearIfStillOwnedValue(lastSensitiveValue);
        lastSensitiveValue = null;
    }

    private void clearIfStillOwnedValue(String expected) {
        try {
            if (!clipboard.hasPrimaryClip()) return;
            ClipData current = clipboard.getPrimaryClip();
            if (current == null || current.getItemCount() == 0) return;
            CharSequence text = current.getItemAt(0).coerceToText(null);
            if (text != null && expected.contentEquals(text)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboard.clearPrimaryClip();
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
                }
            }
        } catch (RuntimeException ignored) {
            // Clipboard access can be restricted by platform/device policy. Never crash the vault.
        } finally {
            if (expected.equals(lastSensitiveValue)) lastSensitiveValue = null;
            pendingClear = null;
        }
    }

    private void cancelPendingClear() {
        if (pendingClear != null) {
            handler.removeCallbacks(pendingClear);
            pendingClear = null;
        }
    }

    private static void markSensitive(ClipDescription description) {
        PersistableBundle extras = description.getExtras();
        if (extras == null) extras = new PersistableBundle();
        // Literal key keeps the hint usable on older Android releases too.
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        if (Build.VERSION.SDK_INT >= 33) {
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
        }
        description.setExtras(extras);
    }
}
