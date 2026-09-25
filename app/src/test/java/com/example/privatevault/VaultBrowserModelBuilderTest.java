package com.example.privatevault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;

public class VaultBrowserModelBuilderTest {

    @Test
    public void browserRowsExposePresentationFieldsOnly() {
        for (Field field : VaultBrowserModel.ItemRow.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase();
            assertFalse(name.contains("password"));
            assertFalse(name.contains("note"));
            assertFalse(name.contains("custom"));
        }
        assertEquals(3, VaultBrowserModel.ItemRow.class.getDeclaredFields().length);
    }

    @Test
    public void searchMatchesSecretBearingSourceWithoutCopyingSecretToRow() {
        VaultItem item = item(41L, "Production", "Login", "operator");
        item.password = "must-not-appear";
        item.notes = "recovery phrase lives offline";
        item.customFields = new LinkedHashMap<>();
        item.customFields.put("Account reference", "blue-orchid");

        VaultBrowserModel model = VaultBrowserModelBuilder.build(
                "blue-orchid", Collections.singletonList(item),
                Collections.emptyList(), Collections.singletonList("Login"));

        assertEquals(1, model.allCategories.directItems.size());
        VaultBrowserModel.ItemRow row = model.allCategories.directItems.get(0);
        assertEquals(41L, row.id);
        assertEquals("Production", row.title);
        assertEquals("operator", row.secondary);
        assertFalse(row.title.contains(item.password));
        assertFalse(row.secondary.contains("blue-orchid"));
    }

    @Test
    public void categoryCountsIncludeCompleteDescendantSubtree() {
        CustomCategory team = category(1L, "Team", "Work");
        CustomCategory project = category(2L, "Project", "Team");
        VaultItem direct = item(1L, "Direct", "Work", "");
        VaultItem nested = item(2L, "Nested", "Project", "");

        VaultBrowserModel model = VaultBrowserModelBuilder.build(
                "", Arrays.asList(direct, nested), Arrays.asList(team, project),
                Collections.singletonList("Work"));

        VaultBrowserModel.CategoryNode work = model.roots.get(0);
        assertEquals(2, work.totalItemCount);
        assertEquals(1, work.children.size());
        assertEquals(1, work.children.get(0).children.size());
    }

    @Test
    public void customDescendantInheritsBuiltInIconFamily() {
        CustomCategory team = category(1L, "Team", "Work");
        CustomCategory project = category(2L, "Project", "Team");

        assertEquals("Work", VaultBrowserModelBuilder.iconFamily(
                "Project", Arrays.asList(team, project)));
    }

    @Test
    public void itemWithoutUsernameUsesCategoryPathAsSecondaryText() {
        CustomCategory team = category(1L, "Team", "Work");
        VaultItem item = item(9L, "VPN", "Team", "");

        VaultBrowserModel model = VaultBrowserModelBuilder.build(
                "", Collections.singletonList(item), Collections.singletonList(team),
                Collections.singletonList("Work"));

        VaultBrowserModel.ItemRow row = model.roots.get(0).children.get(0).directItems.get(0);
        assertEquals("Work / Team", row.secondary);
        assertTrue(model.allCategories.directItems.contains(row) == false);
        assertEquals("Work / Team", model.allCategories.directItems.get(0).secondary);
    }

    private static VaultItem item(long id, String title, String category, String username) {
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
