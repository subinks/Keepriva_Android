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
public class KeeprivaSecuritySettingsTest extends KeeprivaTestBase {

    @Test
    public void securityRequiresReauthentication() {
        createTestVault();

        onView(withContentDescription("Security")).perform(scrollTo(), click());

        onView(withHint("Master password")).check(matches(isDisplayed()));
        onView(withText("Continue")).check(matches(isDisplayed()));
    }

    @Test
    public void wrongSecurityPassword_keepsDialogOpen() {
        createTestVault();

        onView(withContentDescription("Security")).perform(scrollTo(), click());
        onView(withHint("Master password"))
                .perform(replaceText("WrongPassword123!"), closeSoftKeyboard());
        onView(withText("Continue")).perform(click());

        onView(withText("Continue")).check(matches(isDisplayed()));
    }

    @Test
    public void correctSecurityPassword_opensSettings() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Change master password")).check(matches(isDisplayed()));
    }

    @Test
    public void securityStatusShowsDefaults() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Lock when screen turns off")).check(matches(isDisplayed()));
        onView(withText("Auto-lock: 30 seconds")).check(matches(isDisplayed()));
        onView(withText("Clipboard timeout: 30s")).check(matches(isDisplayed()));
    }

    @Test
    public void autoLockDialog_listsAllOptions() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Auto-lock: 30 seconds")).perform(click());

        onView(withText("Immediately")).check(matches(isDisplayed()));
        onView(withText("30 seconds")).check(matches(isDisplayed()));
        onView(withText("1 minute")).check(matches(isDisplayed()));
        onView(withText("5 minutes")).check(matches(isDisplayed()));
    }

    @Test
    public void autoLockCanBeChangedToImmediate() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Auto-lock: 30 seconds")).perform(click());
        onView(withText("Immediately")).perform(click());
        onView(withText("Save")).perform(click());

        onView(withHint("Search title, username, phone, website or notes"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }

    @Test
    public void clipboardDialog_listsAllOptions() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Clipboard timeout: 30s")).perform(click());

        onView(withText("15 seconds")).check(matches(isDisplayed()));
        onView(withText("30 seconds (recommended)")).check(matches(isDisplayed()));
        onView(withText("60 seconds")).check(matches(isDisplayed()));
        onView(withText("Never auto-clear")).check(matches(isDisplayed()));
    }

    @Test
    public void clipboardCanBeChangedTo15Seconds() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Clipboard timeout: 30s")).perform(click());
        onView(withText("15 seconds")).perform(click());
        onView(withText("Save")).perform(click());

        onView(withHint("Search title, username, phone, website or notes"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }

    @Test
    public void changePasswordDialog_opens() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Change master password")).perform(click());

        onView(withHint("Current master password")).check(matches(isDisplayed()));
        onView(withHint("New master password (12+ characters)"))
                .check(matches(isDisplayed()));
        onView(withHint("Confirm new master password"))
                .check(matches(isDisplayed()));
    }

    @Test
    public void wrongCurrentPassword_doesNotChangePassword() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Change master password")).perform(click());

        onView(withHint("Current master password"))
                .perform(replaceText("WrongPassword123!"), closeSoftKeyboard());
        onView(withHint("New master password (12+ characters)"))
                .perform(replaceText("NewKeepriva123!"), closeSoftKeyboard());
        onView(withHint("Confirm new master password"))
                .perform(replaceText("NewKeepriva123!"), closeSoftKeyboard());

        onView(withText("Change")).perform(click());

        onView(withText("Change")).check(matches(isDisplayed()));
    }

    @Test
    public void mismatchedNewPasswords_areRejected() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Change master password")).perform(click());

        onView(withHint("Current master password"))
                .perform(replaceText(TEST_PASSWORD), closeSoftKeyboard());
        onView(withHint("New master password (12+ characters)"))
                .perform(replaceText("NewKeepriva123!"), closeSoftKeyboard());
        onView(withHint("Confirm new master password"))
                .perform(replaceText("DifferentNew123!"), closeSoftKeyboard());

        onView(withText("Change")).perform(click());

        onView(withText("Change")).check(matches(isDisplayed()));
    }

    @Test
    public void biometricSettings_isReachable() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Biometric unlock: Disabled")).perform(click());

        onView(withText("Enable biometric unlock?")).check(matches(isDisplayed()));
    }
}
