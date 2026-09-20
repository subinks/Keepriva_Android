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

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class KeeprivaAuthHomeTest extends KeeprivaTestBase {

    @Test
    public void setupScreen_containsRequiredControls() {
        onView(withText("Create Keepriva")).check(matches(isDisplayed()));
        onView(withHint("Master password (12+ characters)")).check(matches(isDisplayed()));
        onView(withHint("Confirm master password")).check(matches(isDisplayed()));
        onView(withText("Create encrypted vault")).check(matches(isDisplayed()));
    }

    @Test
    public void weakPassword_isRejected() {
        onView(withHint("Master password (12+ characters)"))
                .perform(replaceText("weak"), closeSoftKeyboard());
        onView(withHint("Confirm master password"))
                .perform(replaceText("weak"), closeSoftKeyboard());

        onView(withText("Create encrypted vault")).perform(click());

        onView(withText("Create encrypted vault")).check(matches(isDisplayed()));
    }

    @Test
    public void passwordWithOnlyTwoCharacterClasses_isRejected() {
        String password = "lowercaseonly123";
        onView(withHint("Master password (12+ characters)"))
                .perform(replaceText(password), closeSoftKeyboard());
        onView(withHint("Confirm master password"))
                .perform(replaceText(password), closeSoftKeyboard());

        onView(withText("Create encrypted vault")).perform(click());

        onView(withText("Create encrypted vault")).check(matches(isDisplayed()));
    }

    @Test
    public void mismatchedPasswords_areRejected() {
        onView(withHint("Master password (12+ characters)"))
                .perform(replaceText(TEST_PASSWORD), closeSoftKeyboard());
        onView(withHint("Confirm master password"))
                .perform(replaceText("Different123!X"), closeSoftKeyboard());

        onView(withText("Create encrypted vault")).perform(click());

        onView(withText("Create encrypted vault")).check(matches(isDisplayed()));
    }

    @Test
    public void validPassword_createsVault() {
        createTestVault();
        onView(withText("Keepriva")).check(matches(isDisplayed()));
    }

    @Test
    public void home_containsCoreActions() {
        createTestVault();

        onView(withContentDescription("Manage categories")).perform(scrollTo()).check(matches(isDisplayed()));
        onView(withContentDescription("Import")).perform(scrollTo()).check(matches(isDisplayed()));
        onView(withContentDescription("Export")).perform(scrollTo()).check(matches(isDisplayed()));
        onView(withContentDescription("Backup & Restore")).perform(scrollTo()).check(matches(isDisplayed()));
    }

    @Test
    public void homeAdditionalActions_canBeScrolledIntoView() {
        createTestVault();

        onView(withText("Preferences")).perform(scrollTo()).check(matches(isDisplayed()));
        onView(withText("Security")).perform(scrollTo()).check(matches(isDisplayed()));
        onView(withText("Lock")).perform(scrollTo()).check(matches(isDisplayed()));
    }

    @Test
    public void explicitLock_returnsToUnlock() {
        createTestVault();
        lockVault();

        onView(withHint("Master password")).check(matches(isDisplayed()));
        onView(withText("Unlock")).check(matches(isDisplayed()));
    }

    @Test
    public void wrongPassword_doesNotUnlock() {
        createTestVault();
        lockVault();

        onView(withHint("Master password"))
                .perform(replaceText("WrongPassword123!"), closeSoftKeyboard());
        onView(withText("Unlock")).perform(click());

        waitForUnlockReady();
        onView(withText("Unlock")).check(matches(isDisplayed()));
    }

    @Test
    public void correctPassword_unlocksAgain() {
        createTestVault();
        lockVault();
        unlockWithTestPassword();
    }

    @Test
    public void emptyVault_showsEmptyState() {
        createTestVault();
        onView(withText("No items yet. Tap Add item to create your first credential or note."))
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }
}
