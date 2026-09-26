package com.example.privatevault;

/** Minimal presentation data for a vault item list row. No password or notes are retained. */
public final class ItemRowModel {
    public final long itemId;
    public final String title;
    public final String categoryLabel;
    public final String secondaryText;

    public ItemRowModel(long itemId, String title, String categoryLabel, String secondaryText) {
        this.itemId = itemId;
        this.title = safe(title);
        this.categoryLabel = safe(categoryLabel);
        this.secondaryText = safe(secondaryText);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}

