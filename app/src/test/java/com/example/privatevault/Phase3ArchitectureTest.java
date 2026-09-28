package com.example.privatevault;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    @Test
    public void waveC1Navigation_isProcessMemoryOnlyAndLifecycleBound() throws Exception {
        assertFalse(java.io.Serializable.class.isAssignableFrom(VaultNavigationState.class));
        for (Class<?> contract : VaultNavigationState.class.getInterfaces()) {
            assertFalse("android.os.Parcelable".equals(contract.getName()));
        }

        String activity = readProjectFile("src/main/java/com/example/privatevault/MainActivity.java");
        String browser = readProjectFile(
                "src/main/java/com/example/privatevault/VaultBrowserController.java");
        String dialogs = readProjectFile(
                "src/main/java/com/example/privatevault/ItemDialogController.java");
        String manifest = readProjectFile("src/main/AndroidManifest.xml");

        assertTrue(activity.contains("browserController.navigateToParentCategory()"));
        assertTrue(activity.contains("@SuppressLint(\"GestureBackNavigation\")"));
        assertFalse(activity.contains("registerOnBackInvokedCallback("));
        assertTrue(manifest.contains("android:enableOnBackInvokedCallback=\"false\""));
        assertTrue(activity.contains("screenRouter.navigate("));
        assertTrue(activity.contains("browserController.restoreNavigationState(browserState)"));
        assertTrue(activity.contains("itemDialogController.clearSessionState()"));
        assertTrue(browser.contains("target.post(() ->"));
        assertTrue(browser.contains("generation != renderGeneration"));
        assertTrue(dialogs.contains("actions.onItemDialogClosed()"));
        assertTrue(dialogs.contains("dialogWorkflow.beginTransition()"));
    }

    private static String readProjectFile(String relative) throws Exception {
        Path path = Paths.get(relative);
        if (!Files.exists(path)) path = Paths.get("app").resolve(relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
