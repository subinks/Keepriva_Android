package com.example.privatevault;

import android.app.Activity;
import android.app.Dialog;
import android.os.Build;
import android.view.Window;
import android.view.WindowManager;

/**
 * Centralizes screen-capture protection for sensitive vault UI.
 *
 * FLAG_SECURE is intentionally applied for the entire Activity lifetime because
 * every Private Vault screen can reveal or lead to sensitive information.
 */
public final class ScreenSecurityManager {
    private ScreenSecurityManager() {}

    public static void protect(Activity activity) {
        if (activity == null) return;
        protectWindow(activity.getWindow());

        // Android 13+ exposes a dedicated control for Recents screenshots.
        // Keep FLAG_SECURE as the primary cross-version control and disable the
        // Recents screenshot path explicitly where the API is available.
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                activity.setRecentsScreenshotEnabled(false);
            } catch (RuntimeException ignored) {
                // FLAG_SECURE remains active if an OEM implementation rejects it.
            }
        }
    }

    public static void protect(Dialog dialog) {
        if (dialog == null) return;
        protectWindow(dialog.getWindow());
    }

    private static void protectWindow(Window window) {
        if (window == null) return;
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
}
