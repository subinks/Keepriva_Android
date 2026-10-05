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
        assertTrue(activity.contains("screenRouter.completeItemSave(categoryName)"));
        assertTrue(activity.contains("browserController.restoreNavigationState(browserState)"));
        assertTrue(activity.contains("itemDialogController.clearSessionState()"));
        assertTrue(browser.contains("target.post(() ->"));
        assertTrue(browser.contains("generation != renderGeneration"));
        assertTrue(browser.contains("private PopupMenu activeQuickAddMenu;"));
        assertTrue(browser.contains("dismissQuickAddMenu();"));
        assertTrue(dialogs.contains("actions.onItemDialogClosed()"));
        assertTrue(dialogs.contains("dialogWorkflow.beginTransition()"));
    }

    @Test
    public void waveC2ToolbarActions_areTypedRoutedAndLifecycleBound() throws Exception {
        String activity = readProjectFile("src/main/java/com/example/privatevault/MainActivity.java");
        String actions = readProjectFile(
                "src/main/java/com/example/privatevault/VaultBrowserActions.java");
        String browser = readProjectFile(
                "src/main/java/com/example/privatevault/VaultBrowserController.java");
        String toolbar = readProjectFile("src/main/res/layout/view_vault_toolbar.xml");

        assertTrue(actions.contains("void onVaultSearchRequested();"));
        assertFalse(actions.contains("void onSearchRequested();"));
        assertTrue(activity.contains("browserState.forScreen(VaultScreen.SEARCH_RESULTS)"));
        assertTrue(activity.contains("returnFromSearchResultsIfPossible()"));
        assertTrue(browser.contains("R.id.toolbar_search"));
        assertTrue(browser.contains("R.id.toolbar_overflow"));
        assertTrue(browser.contains("private PopupMenu activeOverflowMenu;"));
        assertTrue(browser.contains("dismissPopupMenus();"));
        assertTrue(browser.contains("VaultBrowserOverflowAction.fromMenuItemId("));
        assertFalse(browser.contains("handleOverflowAction(String"));
        assertTrue(browser.contains("actions.onExportRequested(selectedCategory)"));
        assertTrue(toolbar.contains("@+id/toolbar_search"));
        assertTrue(toolbar.contains("@+id/toolbar_action"));
        assertTrue(toolbar.contains("@+id/toolbar_overflow"));
    }

    @Test
    public void waveDInstrumentation_usesCategoryFirstChromeAndDeterministicVisuals()
            throws Exception {
        String[] instrumentationFiles = {
                "KeeprivaAuthHomeTest.java",
                "KeeprivaBiometricCiTest.java",
                "KeeprivaCategoryDeletionTest.java",
                "KeeprivaCategoryPreferencesTest.java",
                "KeeprivaDataTransferTest.java",
                "KeeprivaItemCrudTest.java",
                "KeeprivaLifecycleRobustnessTest.java",
                "KeeprivaSecuritySettingsTest.java",
                "KeeprivaTestBase.java",
                "KeeprivaUiSmokeTest.java"
        };
        StringBuilder instrumentation = new StringBuilder();
        for (String file : instrumentationFiles) {
            instrumentation.append(readProjectFile(
                    "src/androidTest/java/com/example/privatevault/" + file));
        }

        String tests = instrumentation.toString();
        String base = readProjectFile(
                "src/androidTest/java/com/example/privatevault/KeeprivaTestBase.java");
        String itemCrud = readProjectFile(
                "src/androidTest/java/com/example/privatevault/KeeprivaItemCrudTest.java");
        String visual = readProjectFile("scripts/ci/run-visual-verification.sh");
        String batchRunner = readProjectFile("scripts/ci/run-instrumentation-batch.sh");

        assertFalse(tests.contains("Search title, username, phone, website or notes"));
        assertFalse(tests.contains("expandsInline"));
        assertFalse(tests.contains("No tree search results"));
        assertTrue(base.contains("void openVaultAction(String actionLabel)"));
        assertTrue(base.contains("withContentDescription(\"More vault actions\")"));
        assertTrue(base.contains("inRoot(isPlatformPopup())"));
        assertTrue(base.contains("CharSequence description = view.getContentDescription();"));
        assertTrue(base.contains("return description != null"));
        assertTrue(base.contains("\"Search vault\".contentEquals(description)"));
        assertTrue(itemCrud.contains("categoryNavigation_opensDedicatedCategory"));
        assertTrue(itemCrud.contains("Empty category Banking"));
        assertTrue(visual.contains("02-vault-root.png"));
        assertTrue(visual.contains("03-vault-nested-category.png"));
        assertTrue(visual.contains("04-vault-empty-category.png"));
        assertTrue(visual.contains("content-desc=\"Empty category Banking\""));
        assertTrue(batchRunner.contains("auth-lifecycle-smoke) readonly EXPECTED_TESTS=32"));
        assertTrue(batchRunner.contains("categories) readonly EXPECTED_TESTS=25"));
        assertTrue(batchRunner.contains("item-core) readonly EXPECTED_TESTS=14"));
        assertTrue(batchRunner.contains("serial-safety-net) readonly EXPECTED_TESTS=95"));
    }

    private static String readProjectFile(String relative) throws Exception {
        Path[] candidates = {
                Paths.get(relative),
                Paths.get("app").resolve(relative),
                Paths.get("..").resolve(relative)
        };
        for (Path path : candidates) {
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new java.io.IOException("Project file not found: " + relative);
    }
}
