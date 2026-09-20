package com.example.privatevault;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Fast top-level smoke coverage.
 *
 * Deeper coverage lives in:
 * KeeprivaAuthHomeTest
 * KeeprivaItemCrudTest
 * KeeprivaCategoryPreferencesTest
 * KeeprivaSecuritySettingsTest
 * KeeprivaDataTransferTest
 * KeeprivaLifecycleRobustnessTest
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class KeeprivaUiSmokeTest {

    private static final String TEST_PASSWORD = "KeeprivaTest123!";
    private ActivityScenario<MainActivity> scenario;

    @Before
    public void setUp() {
        clearAppState();
        scenario = ActivityScenario.launch(MainActivity.class);
    }

    @After
    public void tearDown() {
        if (scenario != null) scenario.close();
        clearAppState();
    }

    private void clearAppState() {
        Context context = ApplicationProvider.getApplicationContext();

        SharedPreferences prefs =
                context.getSharedPreferences("vault_config", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();

        context.deleteDatabase("private_vault.db");
        context.deleteDatabase("private_vault");
        context.deleteDatabase("keepriva.db");
    }

    private void createTestVault() {
        onView(withHint("Master password (12+ characters)"))
                .perform(replaceText(TEST_PASSWORD), closeSoftKeyboard());

        onView(withHint("Confirm master password"))
                .perform(replaceText(TEST_PASSWORD), closeSoftKeyboard());

        onView(withText("Create encrypted vault")).perform(click());

        onView(withHint("Search title, username, phone, website or notes"))
                .check(matches(isDisplayed()));
    }

    private void waitForUnlockReady() {
        final long deadline = android.os.SystemClock.uptimeMillis() + 45000L;

        while (android.os.SystemClock.uptimeMillis() < deadline) {
            final java.util.concurrent.atomic.AtomicBoolean ready =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

            scenario.onActivity(activity ->
                    ready.set(findEnabledUnlock(activity.getWindow().getDecorView())));

            if (ready.get()) {
                androidx.test.platform.app.InstrumentationRegistry
                        .getInstrumentation()
                        .waitForIdleSync();
                return;
            }

            android.os.SystemClock.sleep(75L);
        }

        throw new AssertionError("Timed out waiting for unlock screen");
    }

    private static boolean findEnabledUnlock(android.view.View view) {
        if (view instanceof android.widget.Button) {
            android.widget.Button button = (android.widget.Button) view;
            if ("Unlock".contentEquals(button.getText()) && button.isEnabled()) return true;
        }

        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (findEnabledUnlock(group.getChildAt(i))) return true;
            }
        }

        return false;
    }
    @Test
    public void freshInstall_showsSetupScreen() {
        onView(withText("Create Keepriva")).check(matches(isDisplayed()));
        onView(withHint("Master password (12+ characters)"))
                .check(matches(isDisplayed()));
        onView(withHint("Confirm master password"))
                .check(matches(isDisplayed()));
        onView(withText("Create encrypted vault"))
                .check(matches(isDisplayed()));
    }

    @Test
    public void createVault_opensHomeScreen() {
        createTestVault();

        onView(withHint("Search title, username, phone, website or notes"))
                .check(matches(isDisplayed()));
        onView(withContentDescription("Import")).perform(scrollTo()).check(matches(isDisplayed()));
        onView(withContentDescription("Export")).perform(scrollTo()).check(matches(isDisplayed()));
        onView(withContentDescription("Backup & Restore")).perform(scrollTo()).check(matches(isDisplayed()));
    }

    @Test
    public void lock_returnsToUnlockScreen() {
        createTestVault();

        onView(withText("Lock")).perform(scrollTo(), click());

        onView(withHint("Master password")).check(matches(isDisplayed()));
        onView(withText("Unlock")).check(matches(isDisplayed()));
    }

    @Test
    public void wrongMasterPassword_doesNotUnlock() {
        createTestVault();

        onView(withText("Lock")).perform(scrollTo(), click());

        onView(withHint("Master password"))
                .perform(replaceText("WrongPassword123!"), closeSoftKeyboard());

        onView(withText("Unlock")).perform(click());

        waitForUnlockReady();
        onView(withText("Unlock")).check(matches(isDisplayed()));
    }

    @Test
    public void addItem_requiresTitle() {
        createTestVault();

        onView(withContentDescription("Add item")).perform(scrollTo(), new androidx.test.espresso.ViewAction() {
            @Override
            public org.hamcrest.Matcher<android.view.View> getConstraints() {
                return isDisplayed();
            }

            @Override
            public String getDescription() {
                return "invoke Add item performClick directly";
            }

            @Override
            public void perform(androidx.test.espresso.UiController uiController,
                                android.view.View view) {
                if (!view.isEnabled() || !view.isClickable()) {
                    throw new AssertionError("Add item must be enabled and clickable");
                }
                if (!view.performClick()) {
                    throw new AssertionError("Add item performClick returned false");
                }
                uiController.loopMainThreadUntilIdle();
            }
        });

        onView(withText("Add vault item")).inRoot(isDialog()).check(matches(isDisplayed()));
        onView(withContentDescription("Save vault item")).inRoot(isDialog()).check(matches(isDisplayed()));
        onView(withContentDescription("Cancel vault item")).inRoot(isDialog()).check(matches(isDisplayed()));

        onView(withContentDescription("Save vault item")).inRoot(isDialog()).perform(click());

        // Validation must keep the add form open.
        onView(withContentDescription("Save vault item")).inRoot(isDialog()).check(matches(isDisplayed()));
        onView(withContentDescription("Cancel vault item")).inRoot(isDialog()).check(matches(isDisplayed()));
    }

    @Test
    public void importDialog_showsBothActions() {
        createTestVault();

        onView(withContentDescription("Import")).perform(scrollTo(), click());

        onView(withText("Step 1 — Save JSON import template"))
                .check(matches(isDisplayed()));
        onView(withText("Step 2 — Import completed JSON template"))
                .check(matches(isDisplayed()));
    }

    @Test
    public void backupDialog_showsBothActions() {
        createTestVault();

        onView(withContentDescription("Backup & Restore")).perform(scrollTo(), click());

        onView(withText("Create encrypted .pvault backup"))
                .check(matches(isDisplayed()));
        onView(withText("Restore encrypted .pvault backup"))
                .check(matches(isDisplayed()));
    }

    @Test
    public void exportWithNoItems_doesNotCrash() {
        createTestVault();

        onView(withContentDescription("Export")).perform(scrollTo(), click());

        onView(withHint("Search title, username, phone, website or notes"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }

    @Test
    public void preferencesDialog_isReachable() {
        createTestVault();

        onView(withContentDescription("Preferences")).perform(scrollTo(), click());

        onView(withText("Preferences")).check(matches(isDisplayed()));
        onView(withText("Enable editing category nesting depth"))
                .check(matches(isDisplayed()));
    }

    @Test
    public void categoriesDialog_isReachable() {
        createTestVault();

        onView(withContentDescription("Manage categories")).perform(scrollTo(), click());

        onView(withText("Categories")).check(matches(isDisplayed()));
        onView(withText("+  New category / subcategory"))
                .inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }
}
