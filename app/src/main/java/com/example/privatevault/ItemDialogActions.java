package com.example.privatevault;

/** Item-dialog events use stable row identifiers and never carry decrypted fields. */
interface ItemDialogActions {
    void onItemSaved(long itemId);
    void onItemDeleted(long itemId);
    void onItemDialogClosed();
}
