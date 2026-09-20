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
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.is;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.After;
import org.junit.Before;

import java.util.concurrent.atomic.AtomicBoolean;

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

        waitForHomeScreen();

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

        // PBKDF2 now runs off the Android main thread.
        waitForHomeScreen();

        onView(withHint("Search title, username, phone, website or notes"))
                .check(matches(isDisplayed()));
    }

    protected void waitForHomeScreen() {
        waitForUiState("home screen", view ->
                view instanceof EditText
                        && "Search title, username, phone, website or notes".contentEquals(
                                ((EditText) view).getHint()));
    }

    protected void waitForUnlockReady() {
        waitForUiState("unlock screen", view ->
                view instanceof Button
                        && "Unlock".contentEquals(((Button) view).getText())
                        && view.isEnabled());
    }

    private interface ViewPredicate {
        boolean matches(View view);
    }

    private void waitForUiState(String description, ViewPredicate predicate) {
        if (scenario == null) throw new AssertionError("ActivityScenario is unavailable");

        final long deadline = SystemClock.uptimeMillis() + 45000L;

        while (SystemClock.uptimeMillis() < deadline) {
            final AtomicBoolean matched = new AtomicBoolean(false);

            scenario.onActivity(activity -> {
                View root = activity.getWindow() == null
                        ? null
                        : activity.getWindow().getDecorView();
                matched.set(root != null && treeMatches(root, predicate));
            });

            if (matched.get()) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                return;
            }

            SystemClock.sleep(75L);
        }

        throw new AssertionError("Timed out waiting for " + description);
    }

    private static boolean treeMatches(View view, ViewPredicate predicate) {
        if (predicate.matches(view)) return true;

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (treeMatches(group.getChildAt(i), predicate)) return true;
            }
        }

        return false;
    }

    protected void openAddItem() {
        /*
         * The "+" action is tiny and its synthetic coordinate click was flaky on
         * the API-35 CI emulator. Invoke the real registered listener directly,
         * then explicitly switch Espresso to the dialog root.
         */
        onView(withContentDescription("Add item")).perform(scrollTo(), performClickDirectly());

        // AlertDialog owns window focus now. Never let Espresso choose the
        // underlying Activity root while the editor dialog is open.
        onView(withText("Add vault item"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));

        onView(withContentDescription("Save vault item"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));

        onView(withContentDescription("Cancel vault item"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));
    }

    protected void typeItemTitle(String titleText) {
        /*
         * Do not use "first visible EditText". The editor is scrollable and a field
         * can legitimately exist outside the current viewport.
         */
        onView(withHint("Title"))
                .inRoot(isDialog())
                .perform(scrollTo(), replaceText(titleText), closeSoftKeyboard());
    }

    protected void createBasicItem(String titleText) {
        openAddItem();
        typeItemTitle(titleText);
        saveItemEditor();
        onView(withText(titleText)).perform(scrollTo()).check(matches(isDisplayed()));
    }

    protected void createLoginItem(String titleText, String username, String password) {
        openAddItem();
        typeItemTitle(titleText);

        onView(withHint("Username / email (optional)"))
                .inRoot(isDialog())
                .perform(scrollTo(), replaceText(username), closeSoftKeyboard());

        onView(withHint("Password (optional)"))
                .inRoot(isDialog())
                .perform(scrollTo(), replaceText(password), closeSoftKeyboard());

        saveItemEditor();
        onView(withText(titleText)).perform(scrollTo()).check(matches(isDisplayed()));
    }

    protected void saveItemEditor() {
        onView(withContentDescription("Save vault item"))
                .inRoot(isDialog())
                .perform(click());

        // A successful save dismisses the dialog. Wait until the Activity's
        // decor window has actually regained focus before touching home views.
        waitForActivityWindowFocus();
    }

    protected void cancelItemEditor() {
        onView(withContentDescription("Cancel vault item"))
                .inRoot(isDialog())
                .perform(click());

        waitForActivityWindowFocus();
    }

    protected void waitForActivityWindowFocus() {
        if (scenario == null) {
            throw new AssertionError("ActivityScenario is not available");
        }

        final long deadline = SystemClock.uptimeMillis() + 5000L;

        while (SystemClock.uptimeMillis() < deadline) {
            final AtomicBoolean focused = new AtomicBoolean(false);

            scenario.onActivity(activity -> {
                View decor = activity.getWindow() == null
                        ? null
                        : activity.getWindow().getDecorView();
                focused.set(decor != null
                        && decor.isAttachedToWindow()
                        && decor.hasWindowFocus()
                        && !decor.isLayoutRequested());
            });

            if (focused.get()) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                return;
            }

            SystemClock.sleep(50L);
        }

        throw new AssertionError(
                "MainActivity did not regain stable window focus within 5 seconds");
    }
    protected void openSecuritySettings() {
        onView(withContentDescription("Security")).perform(scrollTo(), click());

        onView(withHint("Master password"))
                .perform(replaceText(TEST_PASSWORD), closeSoftKeyboard());

        onView(withText("Continue")).perform(click());

        onView(withText("Change master password")).check(matches(isDisplayed()));
    }

    protected void createFolderCategory(String name) {
        onView(withContentDescription("Manage categories")).perform(scrollTo(), click());
        onView(withText("+  New category / subcategory"))
                .perform(scrollTo(), click());

        onView(withHint("Category name"))
                .perform(replaceText(name), closeSoftKeyboard());

        onView(withText("Save")).perform(click());

        onView(withHint("Search title, username, phone, website or notes"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }

    protected void selectHomeCategory(String label) {
        onView(withContentDescription("Open category " + label))
                .perform(scrollTo(), click());
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
