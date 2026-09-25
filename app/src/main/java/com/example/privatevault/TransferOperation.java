package com.example.privatevault;

/** Supported system-picker operations; contains no URI, payload, password, or key. */
enum TransferOperation {
    ENTRY_EXPORT,
    TEMPLATE_EXPORT,
    JSON_IMPORT,
    BACKUP_EXPORT,
    BACKUP_RESTORE
}
