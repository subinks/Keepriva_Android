package com.example.privatevault;

import android.app.AlertDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Activity-owned registry for dialogs that must be dismissed on lock/destruction.
 * The registry deliberately stores no dialog content or feature state.
 */
final class DialogRegistry implements VaultController {
    private final List<DialogHandle> dialogs = new ArrayList<>();
    private boolean closed;
    private boolean dismissing;

    AlertDialog show(AlertDialog dialog) {
        Objects.requireNonNull(dialog, "dialog");
        if (!register(new AlertDialogHandle(dialog))) return dialog;
        dialog.show();
        return dialog;
    }

    synchronized boolean register(DialogHandle dialog) {
        Objects.requireNonNull(dialog, "dialog");
        if (closed || dismissing) return false;
        dialogs.removeIf(existing -> !existing.isShowing());
        dialogs.add(dialog);
        return true;
    }

    void dismissAll() {
        List<DialogHandle> closing;
        synchronized (this) {
            if (dismissing) return;
            dismissing = true;
            closing = new ArrayList<>(dialogs);
            dialogs.clear();
        }
        try {
            for (int index = closing.size() - 1; index >= 0; index--) {
                DialogHandle dialog = closing.get(index);
                if (dialog.isShowing()) dialog.dismiss();
            }
        } finally {
            synchronized (this) {
                dismissing = false;
            }
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        dismissAll();
    }

    synchronized int sizeForTesting() {
        return dialogs.size();
    }

    synchronized boolean isClosedForTesting() {
        return closed;
    }

    interface DialogHandle {
        boolean isShowing();
        void dismiss();
    }

    private static final class AlertDialogHandle implements DialogHandle {
        private final AlertDialog dialog;

        private AlertDialogHandle(AlertDialog dialog) {
            this.dialog = dialog;
        }

        @Override
        public boolean isShowing() {
            return dialog.isShowing();
        }

        @Override
        public void dismiss() {
            dialog.dismiss();
        }
    }
}
