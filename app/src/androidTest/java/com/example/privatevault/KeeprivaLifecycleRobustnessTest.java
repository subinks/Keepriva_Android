package com.example.privatevault;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
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
public class KeeprivaLifecycleRobustnessTest extends KeeprivaTestBase {

    @Test
    public void recreateWhileOnSetup_doesNotCrash() {
        scenario.recreate();

        onView(withText("Create Keepriva")).check(matches(isDisplayed()));
    }

    @Test
    public void recreateAfterVaultCreated_requiresUnlockOrShowsVaultSafely() {
        createTestVault();

        scenario.recreate();

        // Current security design may deliberately require unlock after recreation.
        // Verify the app is still in a usable secure state.
        try {
            onView(withText("Unlock")).check(matches(isDisplayed()));
        } catch (Throwable ignored) {
            onView(withHint("Search title, username, phone, website or notes"))
                    .check(matches(isDisplayed()));
        }
    }

    @Test
    public void repeatedLockUnlock_cycleWorks() {
        createTestVault();

        lockVault();
        unlockWithTestPassword();
        lockVault();
        unlockWithTestPassword();
    }

    @Test
    public void cancelAddDialog_returnsToUsableHome() {
        createTestVault();
        openAddItem();

        cancelItemEditor();

        onView(withHint("Search title, username, phone, website or notes"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }

    @Test
    public void cancelSecurityReauth_returnsToUsableHome() {
        createTestVault();

        onView(withText("Security"))
                .perform(androidx.test.espresso.action.ViewActions.scrollTo(), click());

        // This is the Security re-authentication dialog, not the vault-item editor.
        // Target its own dialog-local Cancel button, then wait until MainActivity
        // has regained focus before asserting the home screen.
        onView(withText("Cancel"))
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .perform(click());

        waitForActivityWindowFocus();

        onView(withHint("Search title, username, phone, website or notes"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }
}
