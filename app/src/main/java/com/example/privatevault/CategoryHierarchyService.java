package com.example.privatevault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure category hierarchy rules shared by the legacy and replacement category UIs. */
final class CategoryHierarchyService {
    private final Set<String> builtInNames;

    CategoryHierarchyService(String[] builtInCategories) {
        Set<String> names = new HashSet<>();
        if (builtInCategories != null) {
            for (String name : builtInCategories) names.add(safe(name).toLowerCase(Locale.ROOT));
        }
        builtInNames = Collections.unmodifiableSet(names);
    }

    /** Root categories have depth 1. Unknown/broken references fail conservatively. */
    int depth(String categoryName, List<CustomCategory> categories) {
        String current = safe(categoryName).trim();
        if (current.isEmpty() || isBuiltIn(current)) return 1;
        Set<String> seen = new HashSet<>();
        int depth = 1;
        while (!current.isEmpty()) {
            if (!seen.add(current.toLowerCase(Locale.ROOT))) {
                return VaultSecurityPreferences.HARD_MAX_CATEGORY_DEPTH + 1;
            }
            CustomCategory category = find(current, categories);
            if (category == null || safe(category.parentName).trim().isEmpty()) return depth;
            String parent = safe(category.parentName).trim();
            depth++;
            if (isBuiltIn(parent)) return depth;
            current = parent;
            if (depth > VaultSecurityPreferences.HARD_MAX_CATEGORY_DEPTH + 1) return depth;
        }
        return depth;
    }

    int deepestDepth(List<CustomCategory> categories) {
        int deepest = 1;
        for (CustomCategory category : safeCategories(categories)) {
            deepest = Math.max(deepest, depth(category.name, categories));
        }
        return deepest;
    }

    String path(String categoryName, List<CustomCategory> categories) {
        String leaf = safe(categoryName).trim();
        if (leaf.isEmpty() || isBuiltIn(leaf)) return leaf;
        List<String> parts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String current = leaf;
        while (!current.isEmpty() && seen.add(current.toLowerCase(Locale.ROOT))) {
            parts.add(current);
            CustomCategory category = find(current, categories);
            if (category == null || safe(category.parentName).trim().isEmpty()) break;
            current = safe(category.parentName).trim();
            if (isBuiltIn(current)) {
                parts.add(current);
                break;
            }
        }
        Collections.reverse(parts);
        return String.join(" / ", parts);
    }

    boolean isDescendantOf(
            String candidateName, String ancestorName, List<CustomCategory> categories) {
        String current = safe(candidateName).trim();
        String ancestor = safe(ancestorName).trim();
        Set<String> seen = new HashSet<>();
        while (!current.isEmpty() && seen.add(current.toLowerCase(Locale.ROOT))) {
            if (current.equals(ancestor)) return true;
            CustomCategory category = find(current, categories);
            if (category == null) return false;
            current = safe(category.parentName).trim();
        }
        return false;
    }

    boolean moveFitsDepth(
            String categoryName,
            String newParentName,
            int maxDepth,
            List<CustomCategory> categories) {
        int parentDepth = safe(newParentName).trim().isEmpty()
                ? 0
                : depth(newParentName, categories);
        int newRootDepth = parentDepth + 1;
        return newRootDepth + subtreeRelativeDepth(categoryName, categories) - 1 <= maxDepth;
    }

    /** Returns null when valid, otherwise the existing user-facing validation error. */
    String validate(List<CustomCategory> categories, int maxDepth) {
        Map<String, String> parents = new LinkedHashMap<>();
        for (CustomCategory category : safeCategories(categories)) {
            String name = safe(category.name).trim();
            if (name.isEmpty()) return "A custom category has no name.";
            String key = name.toLowerCase(Locale.ROOT);
            if (parents.containsKey(key)) return "Duplicate custom category: " + name;
            parents.put(key, safe(category.parentName).trim());
        }
        for (CustomCategory category : safeCategories(categories)) {
            String current = safe(category.name).trim();
            Set<String> seen = new HashSet<>();
            int depth = 1;
            while (!current.isEmpty()) {
                String lower = current.toLowerCase(Locale.ROOT);
                if (!seen.add(lower)) return "Category cycle detected around: " + category.name;
                String parent = parents.get(lower);
                if (parent == null || parent.isEmpty()) break;
                depth++;
                if (depth > maxDepth) {
                    return "Category '" + category.name
                            + "' exceeds the configured maximum depth of " + maxDepth + ".";
                }
                if (isBuiltIn(parent)) break;
                if (!parents.containsKey(parent.toLowerCase(Locale.ROOT))) {
                    return "Category '" + category.name
                            + "' references missing parent '" + parent + "'.";
                }
                current = parent;
            }
        }
        return null;
    }

    int subtreeRelativeDepth(String categoryName, List<CustomCategory> categories) {
        int max = 1;
        for (CustomCategory category : safeCategories(categories)) {
            if (isDescendantOf(category.name, categoryName, categories)) {
                int relative = depth(category.name, categories) - depth(categoryName, categories) + 1;
                max = Math.max(max, relative);
            }
        }
        return max;
    }

    private boolean isBuiltIn(String name) {
        return builtInNames.contains(safe(name).toLowerCase(Locale.ROOT));
    }

    private static CustomCategory find(String name, List<CustomCategory> categories) {
        for (CustomCategory category : safeCategories(categories)) {
            if (safe(category.name).equalsIgnoreCase(safe(name))) return category;
        }
        return null;
    }

    private static List<CustomCategory> safeCategories(List<CustomCategory> categories) {
        return categories == null ? Collections.emptyList() : categories;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
