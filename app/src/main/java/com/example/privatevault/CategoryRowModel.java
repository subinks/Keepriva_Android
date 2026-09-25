package com.example.privatevault;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Non-sensitive presentation data for one category row. */
public final class CategoryRowModel {
    public final long stableId;
    public final String name;
    public final String label;
    public final int entryCount;
    public final int depth;
    public final boolean expanded;

    public CategoryRowModel(
            String name,
            String label,
            int entryCount,
            int depth,
            boolean expanded) {
        this.name = safe(name);
        this.label = safe(label);
        this.entryCount = Math.max(0, entryCount);
        this.depth = Math.max(0, depth);
        this.expanded = expanded;
        this.stableId = stableIdFor(this.name);
    }

    static long stableIdFor(String value) {
        byte[] bytes = safe(value).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L;
        for (byte valueByte : bytes) {
            hash ^= valueByte & 0xffL;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}

