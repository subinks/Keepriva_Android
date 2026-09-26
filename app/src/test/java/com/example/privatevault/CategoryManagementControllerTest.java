package com.example.privatevault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class CategoryManagementControllerTest {

    @Test
    public void parseUniqueLines_trimsDropsBlankAndPreservesFirstOccurrence() {
        assertEquals(
                Arrays.asList("Member ID", "PIN", "Recovery Email"),
                CategoryManagementController.parseUniqueLines(
                        " Member ID \n\nPIN\nMember ID\n Recovery Email "));
    }

    @Test
    public void categorySubtreeNames_includesEveryNestedDescendant() {
        List<CustomCategory> categories = Arrays.asList(
                category(1L, "Team", "Work"),
                category(2L, "Project", "Team"),
                category(3L, "Archive", "Project"),
                category(4L, "Family", "Personal"));

        Set<String> subtree = CategoryManagementController.categorySubtreeNames(
                "Work", categories);

        assertEquals(4, subtree.size());
        assertTrue(subtree.contains("work"));
        assertTrue(subtree.contains("team"));
        assertTrue(subtree.contains("project"));
        assertTrue(subtree.contains("archive"));
        assertFalse(subtree.contains("family"));
    }

    @Test
    public void categorySubtreeNames_isCaseInsensitiveAndCycleSafe() {
        List<CustomCategory> categories = Arrays.asList(
                category(1L, "Child", "ROOT"),
                category(2L, "Root", "Child"));

        Set<String> subtree = CategoryManagementController.categorySubtreeNames(
                "Root", categories);

        assertEquals(2, subtree.size());
        assertTrue(subtree.contains("root"));
        assertTrue(subtree.contains("child"));
    }

    @Test
    public void copyCategory_detachesMutableFieldDefinitions() {
        CustomCategory original = category(7L, "Membership", "Personal");
        original.fields.add("Member ID");
        original.sensitiveFields.add("PIN");

        CustomCategory copy = CategoryManagementController.copyCategory(original);
        copy.name = "Changed";
        copy.fields.add("Tier");
        copy.sensitiveFields.clear();

        assertEquals("Membership", original.name);
        assertEquals(Arrays.asList("Member ID"), original.fields);
        assertEquals(Arrays.asList("PIN"), original.sensitiveFields);
    }

    private static CustomCategory category(long id, String name, String parent) {
        CustomCategory category = new CustomCategory();
        category.id = id;
        category.name = name;
        category.parentName = parent;
        return category;
    }
}
