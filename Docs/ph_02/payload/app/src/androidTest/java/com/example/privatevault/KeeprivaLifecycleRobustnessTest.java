package com.example.privatevault;

import android.content.Context;

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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.lifecycle.Lifecycle;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class KeeprivaLifecycleRobustnessTest extends KeeprivaTestBase {

    @Test
    public void routerStartsAtVaultBrowser_afterSuccessfulSetup() {
        createTestVault();

        onView(withContentDescription("Screen vault browser"))
                .check(matches(isDisplayed()));

        scenario.onActivity(activity -> {
            assertEquals(VaultScreen.VAULT_BROWSER, activity.currentScreenForTesting());
            assertEquals(0, activity.navigationBackStackSizeForTesting());
            assertEquals(1, activity.rootChildCountForTesting());
        });
    }

    @Test
    public void explicitLock_clearsNavigationAndShowsUnlockRoot() {
        createTestVault();

        lockVault();

        onView(withContentDescription("Screen unlock"))
                .check(matches(isDisplayed()));
        scenario.onActivity(activity -> {
            assertEquals(VaultScreen.UNLOCK, activity.currentScreenForTesting());
            assertEquals(0, activity.navigationBackStackSizeForTesting());
            assertFalse(activity.hasSessionKeyForTesting());
            VaultNavigationState state = activity.navigationStateForTesting();
            assertNotNull(state);
            assertEquals("", state.searchQuery());
            assertEquals(VaultNavigationState.NO_ID, state.selectedItemId());
            assertEquals(VaultNavigationState.NO_ID, state.selectedHistoryVersionId());
        });
    }

    @Test
    public void recreation_discardsInMemoryBrowserQueryAndRequiresUnlock() {
        createTestVault();
        onView(withHint("Search title, username, phone, website or notes"))
                .perform(replaceText("sensitive query"), closeSoftKeyboard());

        scenario.recreate();

        onView(withContentDescription("Screen unlock"))
                .check(matches(isDisplayed()));
        scenario.onActivity(activity -> {
            assertEquals(VaultScreen.UNLOCK, activity.currentScreenForTesting());
            assertEquals(0, activity.navigationBackStackSizeForTesting());
            assertFalse(activity.hasSessionKeyForTesting());
            assertEquals("", activity.navigationStateForTesting().searchQuery());
        });
    }

    @Test
    public void activityOwnedRoot_keepsSingleChildAcrossSecureTransitions() {
        scenario.onActivity(activity -> assertEquals(1, activity.rootChildCountForTesting()));

        createTestVault();
        scenario.onActivity(activity -> assertEquals(1, activity.rootChildCountForTesting()));

        lockVault();
        scenario.onActivity(activity -> assertEquals(1, activity.rootChildCountForTesting()));

        unlockWithTestPassword();
        scenario.onActivity(activity -> assertEquals(1, activity.rootChildCountForTesting()));
    }

    @Test
    public void immediateAutoLock_clearsRouterHistoryAndSession() {
        createTestVault();
        scenario.onActivity(activity -> activity
                .getSharedPreferences("vault_config", Context.MODE_PRIVATE)
                .edit()
                .putLong("auto_lock_ms_v1", 0L)
                .commit());

        scenario.moveToState(Lifecycle.State.CREATED);
        scenario.moveToState(Lifecycle.State.RESUMED);

        waitForUnlockReady();
        onView(withContentDescription("Screen unlock"))
                .check(matches(isDisplayed()));
        scenario.onActivity(activity -> {
            assertEquals(VaultScreen.UNLOCK, activity.currentScreenForTesting());
            assertEquals(0, activity.navigationBackStackSizeForTesting());
            assertFalse(activity.hasSessionKeyForTesting());
        });
    }

    @Test
    public void screenOffLock_clearsRouterHistoryAndSession() {
        createTestVault();

        // Exercise the same handler used by the protected ACTION_SCREEN_OFF receiver.
        // Instrumentation applications cannot legally emit that protected broadcast.
        scenario.onActivity(MainActivity::triggerScreenOffForTesting);

        waitForUnlockReady();
        onView(withContentDescription("Screen unlock"))
                .check(matches(isDisplayed()));
        scenario.onActivity(activity -> {
            assertEquals(VaultScreen.UNLOCK, activity.currentScreenForTesting());
            assertEquals(0, activity.navigationBackStackSizeForTesting());
            assertFalse(activity.hasSessionKeyForTesting());
        });
    }

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
