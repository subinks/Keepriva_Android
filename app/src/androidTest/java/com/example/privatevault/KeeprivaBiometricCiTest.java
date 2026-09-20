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

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * CI-only test that creates a fresh vault, enables biometric unlock through the
 * real application UI, then leaves Keepriva on the biometric-capable lock screen.
 *
 * The workflow repeatedly sends emulator fingerprint touches while this test
 * waits for Android BiometricPrompt to complete.
 */
@RunWith(AndroidJUnit4.class)
public class KeeprivaBiometricCiTest {

    private static final String PASSWORD = "KeeprivaTest123!";

    @Test
    public void enableBiometricAndLeaveAppLockedForScreenshot() throws Exception {
        String enabled = InstrumentationRegistry.getArguments()
                .getString("keeprivaBiometricCi", "0");

        Assume.assumeTrue("CI biometric setup test is opt-in", "1".equals(enabled));

        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs =
                context.getSharedPreferences("vault_config", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        context.deleteDatabase("private_vault.db");
        context.deleteDatabase("private_vault");

        ActivityScenario<MainActivity> scenario =
                ActivityScenario.launch(MainActivity.class);

        onView(withHint("Master password (12+ characters)"))
                .perform(replaceText(PASSWORD), closeSoftKeyboard());
        onView(withHint("Confirm master password"))
                .perform(replaceText(PASSWORD), closeSoftKeyboard());
        onView(withText("Create encrypted vault")).perform(click());

        onView(withText("Security")).perform(scrollTo(), click());
        onView(withHint("Master password"))
                .perform(replaceText(PASSWORD), closeSoftKeyboard());
        onView(withText("Continue")).perform(click());

        onView(withText("Biometric unlock: Disabled")).perform(click());
        onView(withText("Enable")).perform(click());

        // The workflow sends virtual fingerprint touches in parallel.
        // Wait for successful enrollment inside Keepriva and the return to home.
        Thread.sleep(6000);

        onView(withText("Lock")).perform(scrollTo(), click());

        onView(withContentDescription("Unlock Keepriva with biometrics"))
                .check(matches(isDisplayed()));

        // Do not clear preferences: the workflow immediately captures the
        // genuine compiled lock screen from the app after this test exits.
        scenario.close();
    }
}
