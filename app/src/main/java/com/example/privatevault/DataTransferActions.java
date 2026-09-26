package com.example.privatevault;

/** Typed picker and completion events; no URI, payload or key crosses the action contract. */
interface DataTransferActions {
    void onPickerRequested(TransferOperation operation, String mimeType, String suggestedName);
    void onTransferCompleted();
    void onTransferClosed();
}
