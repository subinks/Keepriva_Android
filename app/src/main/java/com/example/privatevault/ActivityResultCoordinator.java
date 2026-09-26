package com.example.privatevault;

/** Maps legacy picker request codes to typed operations and checks picker elapsed time. */
final class ActivityResultCoordinator {
    private TransferOperation operation;
    private long startedAt;

    static int requestCode(TransferOperation operation) {
        switch (operation) {
            case ENTRY_EXPORT: return 7001;
            case TEMPLATE_EXPORT: return 7002;
            case JSON_IMPORT: return 7003;
            case BACKUP_EXPORT: return 7004;
            case BACKUP_RESTORE: return 7005;
            default: throw new IllegalArgumentException("Unsupported transfer operation");
        }
    }

    void begin(TransferOperation requested, long now) {
        if (requested == null || operation != null) throw new IllegalStateException("Picker is already active");
        operation = requested;
        startedAt = now;
    }

    boolean isPickerInProgress() { return operation != null; }

    Completed complete(int requestCode, long now, long timeoutMs, boolean unlocked) {
        if (operation == null || requestCode(operation) != requestCode) return null;
        TransferOperation finished = operation;
        boolean lock = unlocked && (timeoutMs == VaultSecurityPreferences.AUTO_LOCK_IMMEDIATELY
                || startedAt <= 0 || now - startedAt >= timeoutMs);
        clear();
        return new Completed(finished, lock);
    }

    void clear() { operation = null; startedAt = 0L; }

    static final class Completed {
        final TransferOperation operation;
        final boolean lockAfterPicker;
        Completed(TransferOperation operation, boolean lockAfterPicker) {
            this.operation = operation;
            this.lockAfterPicker = lockAfterPicker;
        }
    }
}
