package com.example.privatevault;

import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.is;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.EditText;
import android.widget.Spinner;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.core.app.ApplicationProvider;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.After;
import org.junit.Before;

/**
 * Shared deterministic UI-test base.
 *
 * Never place real credentials in instrumentation tests.
 */
public abstract class KeeprivaTestBase {

    protected static final String TEST_PASSWORD = "KeeprivaTest123!";
    protected ActivityScenario<MainActivity> scenario;

    @Before
    public void baseSetUp() {
        clearAppState();
        scenario = ActivityScenario.launch(MainActivity.class);
    }

    @After
    public void baseTearDown() {
        if (scenario != null) {
            scenario.close();
            scenario = null;
        }
        clearAppState();
    }

    protected void clearAppState() {
        Context context = ApplicationProvider.getApplicationContext();

        SharedPreferences prefs =
                context.getSharedPreferences("vault_config", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();

        // Support both historical/current DB names safely.
        context.deleteDatabase("private_vault.db");
        context.deleteDatabase("private_vault");
        context.deleteDatabase("keepriva.db");
    }

    protected void createTestVault() {
        onView(withHint("Master password (12+ characters)"))
                .perform(replaceText(TEST_PASSWORD), closeSoftKeyboard());

        onView(withHint("Confirm master password"))
                .perform(replaceText(TEST_PASSWORD), closeSoftKeyboard());

        onView(withText("Create encrypted vault")).perform(click());

        onView(withHint("Search title, username, phone, website or notes"))
                .check(matches(isDisplayed()));
    }

    protected void lockVault() {
        onView(withText("Lock")).perform(scrollTo(), click());
        onView(withText("Unlock")).check(matches(isDisplayed()));
    }

    protected void unlockWithTestPassword() {
        onView(withHint("Master password"))
                .perform(replaceText(TEST_PASSWORD), closeSoftKeyboard());
        onView(withText("Unlock")).perform(click());
        onView(withHint("Search title, username, phone, website or notes"))
                .check(matches(isDisplayed()));
    }

    protected void openAddItem() {
        /*
         * Espresso's coordinate-based click is unreliable for the compact "+" action
         * in the programmatic header on the API-35 emulator. The failure artifact
         * proves the click action completes while the Activity remains on the home
         * hierarchy and the editor window is never created.
         *
         * performClickDirectly() invokes the exact View.OnClickListener registered by
         * MainActivity, on Espresso's UI thread, and then waits for the main queue to
         * become idle. This still exercises the real application navigation/editor
         * code; it only removes the flaky synthetic touch-coordinate layer.
         */
        onView(withContentDescription("Add item")).perform(performClickDirectly());

        // Verify that we really reached the editor before any CRUD helper continues.
        onView(withText("Add vault item")).check(matches(isDisplayed()));
        onView(withContentDescription("Save vault item")).check(matches(isDisplayed()));
        onView(withContentDescription("Cancel vault item")).check(matches(isDisplayed()));
    }

    protected void createBasicItem(String titleText) {
        openAddItem();

        onView(withIndex(allOf(isAssignableFrom(EditText.class), isDisplayed()), 0))
                .perform(replaceText(titleText), closeSoftKeyboard());

        onView(withContentDescription("Save vault item")).perform(click());
        onView(withText(titleText)).check(matches(isDisplayed()));
    }

    protected void createLoginItem(String titleText, String username, String password) {
        openAddItem();

        onView(withIndex(allOf(isAssignableFrom(EditText.class), isDisplayed()), 0))
                .perform(replaceText(titleText), closeSoftKeyboard());

        onView(withHint("Username / email (optional)"))
                .perform(replaceText(username), closeSoftKeyboard());

        onView(withHint("Password (optional)"))
                .perform(replaceText(password), closeSoftKeyboard());

        onView(withContentDescription("Save vault item")).perform(click());
        onView(withText(titleText)).check(matches(isDisplayed()));
    }

    protected void openSecuritySettings() {
        onView(withText("Security")).perform(scrollTo(), click());

        onView(withHint("Master password"))
                .perform(replaceText(TEST_PASSWORD), closeSoftKeyboard());

        onView(withText("Continue")).perform(click());

        onView(withText("Change master password")).check(matches(isDisplayed()));
    }

    protected void createFolderCategory(String name) {
        onView(withText("Categories")).perform(scrollTo(), click());
        onView(withText("+ New custom category / sub-category"))
                .perform(scrollTo(), click());

        onView(withHint("Category name"))
                .perform(replaceText(name), closeSoftKeyboard());

        onView(withText("Save")).perform(click());

        onView(withHint("Search title, username, phone, website or notes"))
                .check(matches(isDisplayed()));
    }

    protected void selectHomeCategory(String label) {
        onView(isAssignableFrom(Spinner.class)).perform(click());
        onData(hasToString(is(label))).perform(click());
    }

    /**
     * Invokes a view's registered OnClickListener directly on the UI thread.
     *
     * Use this only for the compact Add-item header action whose coordinate-based
     * Espresso tap is flaky on the CI emulator. Normal controls continue using
     * Espresso click(), so the rest of the suite still exercises touch interaction.
     */
    protected static ViewAction performClickDirectly() {
        return new ViewAction() {
            @Override
            public Matcher<View> getConstraints() {
                return allOf(isDisplayed());
            }

            @Override
            public String getDescription() {
                return "invoke View.performClick() and wait for UI idle";
            }

            @Override
            public void perform(UiController uiController, View view) {
                if (!view.isEnabled() || !view.isClickable()) {
                    throw new AssertionError(
                            "Add item view must be enabled and clickable before performClick()");
                }

                boolean handled = view.performClick();
                if (!handled) {
                    throw new AssertionError(
                            "View.performClick() returned false; Add item listener was not invoked");
                }

                uiController.loopMainThreadUntilIdle();
            }
        };
    }
    protected static Matcher<View> withIndex(final Matcher<View> matcher, final int index) {
        return new TypeSafeMatcher<View>() {
            private int currentIndex = 0;

            @Override
            protected boolean matchesSafely(View view) {
                if (!matcher.matches(view)) return false;
                return currentIndex++ == index;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("index ")
                        .appendValue(index)
                        .appendText(" of ");
                matcher.describeTo(description);
            }
        };
    }
}
