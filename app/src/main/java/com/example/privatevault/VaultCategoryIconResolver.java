package com.example.privatevault;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Resolves a non-sensitive semantic icon family without retaining category definitions. */
final class VaultCategoryIconResolver {
    private VaultCategoryIconResolver() { }

    static String resolve(String categoryName, List<CustomCategory> categories) {
        String current = safe(categoryName).trim();
        Set<String> visited = new HashSet<>();
        while (!current.isEmpty() && visited.add(current.toLowerCase(Locale.ROOT))) {
            String builtInFamily = builtInFamily(current);
            if (builtInFamily != null) return builtInFamily;
            CustomCategory custom = find(current, categories);
            if (custom == null) break;
            String parent = safe(custom.parentName).trim();
            if (parent.isEmpty()) return "Custom";
            current = parent;
        }
        return "Custom";
    }

    private static String builtInFamily(String value) {
        String lower = safe(value).toLowerCase(Locale.ROOT);
        if ("login".equals(lower)) return "Login";
        if ("website".equals(lower)) return "Website";
        if ("app".equals(lower)) return "App";
        if ("contact".equals(lower)) return "Contact";
        if ("banking".equals(lower)) return "Banking";
        if ("work".equals(lower)) return "Work";
        if ("personal".equals(lower)) return "Personal";
        if ("secure note".equals(lower)) return "Secure Note";
        if ("other".equals(lower)) return "Other";
        return null;
    }

    private static CustomCategory find(String name, List<CustomCategory> categories) {
        if (categories == null) return null;
        for (CustomCategory category : categories) {
            if (safe(category.name).equalsIgnoreCase(safe(name))) return category;
        }
        return null;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
