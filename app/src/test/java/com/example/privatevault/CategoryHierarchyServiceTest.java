package com.example.privatevault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CategoryHierarchyServiceTest {
    private final CategoryHierarchyService service =
            new CategoryHierarchyService(new String[] {"Login", "Work", "Other"});

    @Test
    public void pathAndDepthIncludeBuiltInParent() {
        List<CustomCategory> categories = Arrays.asList(
                category(1L, "Team", "Work"),
                category(2L, "Project", "Team"));

        assertEquals(3, service.depth("Project", categories));
        assertEquals(3, service.deepestDepth(categories));
        assertEquals("Work / Team / Project", service.path("Project", categories));
    }

    @Test
    public void descendantTraversalHandlesNestedAndUnrelatedCategories() {
        List<CustomCategory> categories = Arrays.asList(
                category(1L, "Team", "Work"),
                category(2L, "Project", "Team"),
                category(3L, "Family", "Other"));

        assertTrue(service.isDescendantOf("Project", "Work", categories));
        assertTrue(service.isDescendantOf("Project", "Project", categories));
        assertFalse(service.isDescendantOf("Family", "Work", categories));
    }

    @Test
    public void moveDepthAccountsForCompleteSubtree() {
        List<CustomCategory> categories = Arrays.asList(
                category(1L, "Team", "Work"),
                category(2L, "Project", "Team"),
                category(3L, "Archive", "Project"),
                category(4L, "Family", "Other"));

        assertFalse(service.moveFitsDepth("Team", "Family", 4, categories));
        assertTrue(service.moveFitsDepth("Team", "", 4, categories));
    }

    @Test
    public void validationRejectsDuplicateMissingParentCycleAndDepth() {
        assertEquals("Duplicate custom category: Team", service.validate(Arrays.asList(
                category(1L, "Team", "Work"), category(2L, "Team", "Other")), 4));
        assertEquals("Category 'Team' references missing parent 'Missing'.",
                service.validate(Collections.singletonList(
                        category(1L, "Team", "Missing")), 4));
        assertEquals("Category cycle detected around: A", service.validate(Arrays.asList(
                category(1L, "A", "B"), category(2L, "B", "A")), 4));
        assertEquals("Category 'B' exceeds the configured maximum depth of 2.",
                service.validate(Arrays.asList(
                        category(1L, "A", "Work"),
                        category(2L, "B", "A"),
                        category(3L, "C", "B")), 2));
    }

    @Test
    public void validHierarchyAndEmptyHierarchyAreAccepted() {
        assertNull(service.validate(Collections.emptyList(), 4));
        assertNull(service.validate(Arrays.asList(
                category(1L, "Team", "Work"),
                category(2L, "Project", "Team")), 4));
    }

    private static CustomCategory category(long id, String name, String parent) {
        CustomCategory category = new CustomCategory();
        category.id = id;
        category.name = name;
        category.parentName = parent;
        return category;
    }
}
