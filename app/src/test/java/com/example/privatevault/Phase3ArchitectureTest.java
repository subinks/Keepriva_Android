package com.example.privatevault;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Phase3ArchitectureTest {

    @Test
    public void browserViewStateIsFinalAndHasNoSecretBearingFields() {
        assertFalse("View state must be final",
                !Modifier.isFinal(VaultBrowserViewState.class.getModifiers()));
        for (Field field : VaultBrowserViewState.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase();
            assertFalse(name.contains("password"));
            assertFalse(name.contains("secret"));
            assertFalse(name.contains("note"));
            assertFalse(name.contains("customfield"));
            assertFalse(VaultItem.class.isAssignableFrom(field.getType()));
        }
    }

    @Test
    public void browserViewStateDefensivelyCopiesPresentationLists() {
        List<CategoryRowModel> source = new ArrayList<>();
        source.add(new CategoryRowModel("Work", "Work", 0, 1, false, "Work"));
        VaultBrowserViewState state = new VaultBrowserViewState(
                "All", "Keepriva", "All", "", source,
                Collections.emptyList(), VaultBrowserViewState.EmptyState.NONE);

        source.clear();
        if (state.subcategories().isEmpty()) fail("State did not copy the source list.");
        try {
            state.subcategories().clear();
            fail("State exposed a mutable category list.");
        } catch (UnsupportedOperationException expected) {
            // Expected immutable contract.
        }
    }
}
