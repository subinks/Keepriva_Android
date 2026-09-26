package com.example.privatevault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class VaultBrowserViewStateBuilderTest {
    private final CategoryHierarchyService hierarchy =
            new CategoryHierarchyService(new String[] {"Login", "Work", "Other"});

    @Test
    public void rootContainsActiveBuiltInsAndSortedCustomRootsOnly() {
        List<CustomCategory> categories = Arrays.asList(
                category(1L, "Zulu", ""),
                category(2L, "Alpha", ""),
                category(3L, "Nested", "Alpha"));

        VaultBrowserViewState state = build(
                "All", Collections.emptyList(), categories, Arrays.asList("Login", "Work"));

        assertEquals("Keepriva", state.title());
        assertEquals("All", state.breadcrumb());
        assertEquals("", state.parentCategory());
        assertEquals(Arrays.asList("Login", "Work", "Alpha", "Zulu"),
                names(state.subcategories()));
        assertTrue(state.items().isEmpty());
    }

    @Test
    public void categoryContainsOnlyDirectChildrenAndDirectItems() {
        List<CustomCategory> categories = Arrays.asList(
                category(1L, "Team", "Work"),
                category(2L, "Project", "Team"));
        List<VaultItem> items = Arrays.asList(
                item(1L, "Work entry", "Work", "operator"),
                item(2L, "Nested entry", "Team", "member"));

        VaultBrowserViewState state = build(
                "Work", items, categories, Collections.singletonList("Work"));

        assertEquals(Collections.singletonList("Team"), names(state.subcategories()));
        assertEquals(1, state.items().size());
        assertEquals(1L, state.items().get(0).itemId);
        assertEquals("Work", state.items().get(0).categoryLabel);
    }

    @Test
    public void breadcrumbAndParentCoverCompleteNestedPath() {
        List<CustomCategory> categories = Arrays.asList(
                category(1L, "Team", "Work"),
                category(2L, "Project", "Team"));

        VaultBrowserViewState state = build(
                "Project", Collections.emptyList(), categories,
                Collections.singletonList("Work"));

        assertEquals("All / Work / Team / Project", state.breadcrumb());
        assertEquals("Team", state.parentCategory());
    }

    @Test
    public void itemRowsExposeTitleAndPathButNotUsernameOrSecrets() {
        CustomCategory team = category(1L, "Team", "Work");
        VaultItem item = item(9L, "", "Team", "private-user");
        item.password = "password-must-not-appear";
        item.notes = "notes-must-not-appear";

        VaultBrowserViewState state = build(
                "Team", Collections.singletonList(item), Collections.singletonList(team),
                Collections.singletonList("Work"));
        ItemRowModel row = state.items().get(0);

        assertEquals("Untitled", row.title);
        assertEquals("Work / Team", row.categoryLabel);
        assertEquals("", row.secondaryText);
    }

    @Test
    public void categoryRowsUseSubtreeCountsAndInheritedIconFamilies() {
        List<CustomCategory> categories = Arrays.asList(
                category(1L, "Team", "Work"),
                category(2L, "Project", "Team"));
        List<VaultItem> items = Arrays.asList(
                item(1L, "Direct", "Team", ""),
                item(2L, "Nested", "Project", ""));

        VaultBrowserViewState state = build(
                "Work", items, categories, Collections.singletonList("Work"));
        CategoryRowModel team = state.subcategories().get(0);

        assertEquals(2, team.entryCount);
        assertEquals("Work", team.iconFamily);
    }

    @Test
    public void emptyStatesDistinguishVaultLeafAndBranch() {
        VaultBrowserViewState emptyVault = build(
                "All", Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList("Login"));
        VaultBrowserViewState emptyLeaf = build(
                "Login", Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList("Login"));
        VaultBrowserViewState emptyBranch = build(
                "Work", Collections.emptyList(),
                Collections.singletonList(category(1L, "Team", "Work")),
                Collections.singletonList("Work"));

        assertEquals(VaultBrowserViewState.EmptyState.EMPTY_VAULT, emptyVault.emptyState());
        assertEquals(VaultBrowserViewState.EmptyState.EMPTY_CATEGORY, emptyLeaf.emptyState());
        assertEquals(VaultBrowserViewState.EmptyState.NO_DIRECT_ITEMS, emptyBranch.emptyState());
    }

    private VaultBrowserViewState build(
            String category,
            List<VaultItem> items,
            List<CustomCategory> categories,
            List<String> activeBuiltIns) {
        return VaultBrowserViewStateBuilder.build(
                category, items, categories, activeBuiltIns, hierarchy);
    }

    private static List<String> names(List<CategoryRowModel> rows) {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        for (CategoryRowModel row : rows) names.add(row.name);
        return names;
    }

    private static VaultItem item(
            long id, String title, String category, String username) {
        VaultItem item = new VaultItem();
        item.id = id;
        item.title = title;
        item.category = category;
        item.username = username;
        return item;
    }

    private static CustomCategory category(long id, String name, String parent) {
        CustomCategory category = new CustomCategory();
        category.id = id;
        category.name = name;
        category.parentName = parent;
        return category;
    }
}
