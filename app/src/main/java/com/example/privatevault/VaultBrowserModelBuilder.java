package com.example.privatevault;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Converts decrypted activity-owned data into a non-secret, short-lived browser model. */
final class VaultBrowserModelBuilder {
    private VaultBrowserModelBuilder() { }

    static VaultBrowserModel build(
            String rawQuery,
            List<VaultItem> items,
            List<CustomCategory> categories,
            List<String> activeBuiltIns) {
        String query = safe(rawQuery).trim().toLowerCase(Locale.ROOT);
        List<VaultItem> safeItems = items == null ? new ArrayList<>() : items;
        List<CustomCategory> safeCategories = categories == null ? new ArrayList<>() : categories;

        List<VaultBrowserModel.ItemRow> allRows = new ArrayList<>();
        for (VaultItem item : safeItems) {
            if (query.isEmpty() || searchText(item).contains(query)) {
                allRows.add(itemRow(item, safeCategories));
            }
        }
        VaultBrowserModel.CategoryNode all = new VaultBrowserModel.CategoryNode(
                "All", "All categories", "All", safeItems.size(),
                allRows, new ArrayList<>());

        List<VaultBrowserModel.CategoryNode> roots = new ArrayList<>();
        for (String builtIn : activeBuiltIns) {
            VaultBrowserModel.CategoryNode node = categoryNode(
                    builtIn, query, safeItems, safeCategories);
            if (query.isEmpty() || branchMatches(builtIn, query, safeItems, safeCategories)) {
                roots.add(node);
            }
        }

        List<CustomCategory> customRoots = new ArrayList<>();
        for (CustomCategory category : safeCategories) {
            if (safe(category.parentName).trim().isEmpty()) customRoots.add(category);
        }
        customRoots.sort((left, right) -> safe(left.name).compareToIgnoreCase(safe(right.name)));
        for (CustomCategory root : customRoots) {
            if (query.isEmpty() || branchMatches(root.name, query, safeItems, safeCategories)) {
                roots.add(categoryNode(root.name, query, safeItems, safeCategories));
            }
        }
        return new VaultBrowserModel(all, roots, safeItems.isEmpty());
    }

    private static VaultBrowserModel.CategoryNode categoryNode(
            String name,
            String query,
            List<VaultItem> items,
            List<CustomCategory> categories) {
        List<VaultBrowserModel.ItemRow> directItems = new ArrayList<>();
        for (VaultItem item : items) {
            if (safe(item.category).equalsIgnoreCase(name)
                    && (query.isEmpty() || searchText(item).contains(query))) {
                directItems.add(itemRow(item, categories));
            }
        }
        directItems.sort((left, right) -> left.title.compareToIgnoreCase(right.title));

        List<CustomCategory> directChildren = new ArrayList<>();
        for (CustomCategory category : categories) {
            if (safe(category.parentName).equalsIgnoreCase(name)) directChildren.add(category);
        }
        directChildren.sort((left, right) -> safe(left.name).compareToIgnoreCase(safe(right.name)));
        List<VaultBrowserModel.CategoryNode> children = new ArrayList<>();
        for (CustomCategory child : directChildren) {
            if (query.isEmpty() || branchMatches(child.name, query, items, categories)) {
                children.add(categoryNode(child.name, query, items, categories));
            }
        }

        return new VaultBrowserModel.CategoryNode(
                name,
                name,
                iconFamily(name, categories),
                countItemsInSubtree(name, items, categories),
                directItems,
                children);
    }

    private static boolean branchMatches(
            String categoryName,
            String query,
            List<VaultItem> items,
            List<CustomCategory> categories) {
        if (safe(categoryName).toLowerCase(Locale.ROOT).contains(query)) return true;
        for (VaultItem item : items) {
            if (safe(item.category).equalsIgnoreCase(categoryName)
                    && searchText(item).contains(query)) return true;
        }
        for (CustomCategory child : categories) {
            if (safe(child.parentName).equalsIgnoreCase(categoryName)
                    && branchMatches(child.name, query, items, categories)) return true;
        }
        return false;
    }

    private static int countItemsInSubtree(
            String categoryName,
            List<VaultItem> items,
            List<CustomCategory> categories) {
        int count = 0;
        for (VaultItem item : items) {
            if (isDescendantOf(item.category, categoryName, categories)) count++;
        }
        return count;
    }

    private static boolean isDescendantOf(
            String candidate,
            String ancestor,
            List<CustomCategory> categories) {
        String current = safe(candidate).trim();
        Set<String> visited = new HashSet<>();
        while (!current.isEmpty() && visited.add(current.toLowerCase(Locale.ROOT))) {
            if (current.equalsIgnoreCase(ancestor)) return true;
            CustomCategory category = findCategory(current, categories);
            if (category == null) return false;
            current = safe(category.parentName).trim();
        }
        return false;
    }

    static String iconFamily(String categoryName, List<CustomCategory> categories) {
        String current = safe(categoryName).trim();
        Set<String> visited = new HashSet<>();
        while (!current.isEmpty() && visited.add(current.toLowerCase(Locale.ROOT))) {
            String lower = current.toLowerCase(Locale.ROOT);
            if ("login".equals(lower)) return "Login";
            if ("website".equals(lower)) return "Website";
            if ("app".equals(lower)) return "App";
            if ("contact".equals(lower)) return "Contact";
            if ("banking".equals(lower)) return "Banking";
            if ("work".equals(lower)) return "Work";
            if ("personal".equals(lower)) return "Personal";
            if ("secure note".equals(lower)) return "Secure Note";
            if ("other".equals(lower)) return "Other";
            CustomCategory custom = findCategory(current, categories);
            if (custom == null) break;
            String parent = safe(custom.parentName).trim();
            if (parent.isEmpty()) return "Custom";
            current = parent;
        }
        return "Custom";
    }

    private static VaultBrowserModel.ItemRow itemRow(
            VaultItem item, List<CustomCategory> categories) {
        String title = safe(item.title).isEmpty() ? "Untitled" : item.title;
        String secondary = safe(item.username).isEmpty()
                ? categoryPath(item.category, categories)
                : safe(item.username);
        return new VaultBrowserModel.ItemRow(item.id, title, secondary);
    }

    private static String categoryPath(String categoryName, List<CustomCategory> categories) {
        String current = safe(categoryName).trim();
        if (current.isEmpty()) return "Other";
        List<String> parts = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        while (!current.isEmpty() && visited.add(current.toLowerCase(Locale.ROOT))) {
            parts.add(0, current);
            CustomCategory category = findCategory(current, categories);
            if (category == null) break;
            current = safe(category.parentName).trim();
        }
        return String.join(" / ", parts);
    }

    private static CustomCategory findCategory(String name, List<CustomCategory> categories) {
        for (CustomCategory category : categories) {
            if (safe(category.name).equalsIgnoreCase(safe(name))) return category;
        }
        return null;
    }

    private static String searchText(VaultItem item) {
        StringBuilder text = new StringBuilder(safe(item.title)).append(' ')
                .append(safe(item.category)).append(' ')
                .append(safe(item.username)).append(' ')
                .append(safe(item.phone1)).append(' ')
                .append(safe(item.phone2)).append(' ')
                .append(safe(item.phone3)).append(' ')
                .append(safe(item.website)).append(' ')
                .append(safe(item.websiteUrl)).append(' ')
                .append(safe(item.notes));
        if (item.customFields != null) {
            for (Map.Entry<String, String> entry : item.customFields.entrySet()) {
                text.append(' ').append(entry.getKey()).append(' ').append(safe(entry.getValue()));
            }
        }
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
