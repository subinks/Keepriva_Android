package com.example.privatevault;

/** Item-dialog events use stable row IDs and a category label, never decrypted fields. */
interface ItemDialogActions {
    void onItemSaved(long itemId, String categoryName);
    void onItemDeleted(long itemId);
    void onItemRestored(long itemId);
    void onItemDialogClosed();
}
