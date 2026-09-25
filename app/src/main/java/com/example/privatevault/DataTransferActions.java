package com.example.privatevault;

/** Activity-bound picker events emitted by the transfer controller. */
interface DataTransferActions {
    void onPickerRequested(TransferOperation operation, String mimeType, String suggestedName);
    void onTransferClosed();
}
