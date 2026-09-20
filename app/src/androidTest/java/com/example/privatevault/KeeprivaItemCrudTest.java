package com.example.privatevault;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class KeeprivaItemCrudTest extends KeeprivaTestBase {

    @Test
    public void addDialog_opens() {
        createTestVault();
        openAddItem();

        onView(withContentDescription("Save vault item")).inRoot(isDialog()).check(matches(isDisplayed()));
        onView(withContentDescription("Cancel vault item")).inRoot(isDialog()).check(matches(isDisplayed()));
    }

    @Test
    public void addWithoutTitle_isRejected() {
        createTestVault();
        openAddItem();

        onView(withContentDescription("Save vault item")).inRoot(isDialog()).perform(click());

        onView(withContentDescription("Save vault item")).inRoot(isDialog()).check(matches(isDisplayed()));
    }

    @Test
    public void cancelAdd_returnsHomeWithoutItem() {
        createTestVault();
        openAddItem();

        cancelItemEditor();

        onView(withText("No items yet. Tap Add item to create your first credential or note."))
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }

    @Test
    public void saveBasicItem_displaysCard() {
        createTestVault();
        createBasicItem("Alpha Item");

        onView(withText("Alpha Item")).perform(scrollTo()).check(matches(isDisplayed()));
    }

    @Test
    public void saveLoginItem_displaysCard() {
        createTestVault();
        createLoginItem("Git Login", "user@example.com", "Secret123!");

        onView(withText("Git Login")).perform(scrollTo()).check(matches(isDisplayed()));
    }

    @Test
    public void detailsDialog_containsActions() {
        createTestVault();
        createBasicItem("Details Item");

        onView(withText("Details Item")).perform(click());

        onView(withText("Details Item"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));

        onView(withText("Edit"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));

        onView(withText("Delete"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));

        onView(withText("Close"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));

        onView(withText("Export this entry"))
                .inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }

    @Test
    public void password_isMaskedInitially() {
        createTestVault();
        createLoginItem("Masked Item", "user@test.com", "Secret123!");

        onView(withText("Masked Item")).perform(click());

        onView(withText("••••••••••••")).check(matches(isDisplayed()));
        onView(withText("Show")).check(matches(isDisplayed()));
        onView(withContentDescription("Copy password securely"))
                .check(matches(isDisplayed()));
    }

    @Test
    public void showAndHidePassword_works() {
        createTestVault();
        createLoginItem("Show Hide", "user@test.com", "Secret123!");

        onView(withText("Show Hide")).perform(click());

        onView(withText("Show"))
                .inRoot(isDialog())
                .perform(scrollTo(), click());

        onView(withText("Secret123!"))
                .inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()));

        onView(withText("Hide"))
                .inRoot(isDialog())
                .perform(scrollTo(), click());

        onView(withText("••••••••••••"))
                .inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }

    @Test
    public void searchMatchingTitle_findsItem() {
        createTestVault();
        createBasicItem("Searchable Credential");

        onView(withHint("Search title, username, phone, website or notes"))
                .perform(scrollTo(), replaceText("Searchable"), closeSoftKeyboard());

        onView(withText("Searchable Credential")).perform(scrollTo()).check(matches(isDisplayed()));
    }

    @Test
    public void searchNoMatch_showsNoMatchingState() {
        createTestVault();
        createBasicItem("Existing Item");

        onView(withHint("Search title, username, phone, website or notes"))
                .perform(scrollTo(), replaceText("does-not-exist"), closeSoftKeyboard());

        onView(withText("No matching items.")).perform(scrollTo()).check(matches(isDisplayed()));
    }

    @Test
    public void categoryNavigation_filtersVisibleEntries_andUpdatesHeading() {
        createTestVault();
        createBasicItem("Login Only Item");

        // A newly-created basic item uses the first built-in category: Login.
        selectHomeCategory("Website");

        onView(withText("Entries — Website"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));

        onView(withText("No matching items."))
                .perform(scrollTo())
                .check(matches(isDisplayed()));

        onView(withText("Login Only Item"))
                .check(doesNotExist());

        // Navigate back to Login and verify the item becomes visible again.
        selectHomeCategory("Login");

        onView(withText("Entries — Login"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));

        onView(withText("Login Only Item"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }

    @Test
    public void search_filtersOutNonMatchingEntries() {
        createTestVault();
        createBasicItem("Alpha Credential");
        createBasicItem("Beta Credential");

        onView(withHint("Search title, username, phone, website or notes"))
                .perform(scrollTo(), replaceText("Alpha"), closeSoftKeyboard());

        onView(withText("Alpha Credential"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));

        onView(withText("Beta Credential"))
                .check(doesNotExist());
    }
    @Test
    public void deleteCancel_keepsItem() {
        createTestVault();
        createBasicItem("Keep Me");

        onView(withText("Keep Me")).perform(click());

        onView(withText("Delete"))
                .inRoot(isDialog())
                .perform(click());

        onView(withText("Delete item?"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));

        onView(withText("Cancel"))
                .inRoot(isDialog())
                .perform(click());

        onView(withText("Close"))
                .inRoot(isDialog())
                .perform(click());

        onView(withText("Keep Me"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }

    @Test
    public void deleteThenUndo_restoresItem() {
        createTestVault();
        createBasicItem("Undo Me");

        onView(withText("Undo Me")).perform(click());

        onView(withText("Undo Me"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));

        onView(withText("Delete"))
                .inRoot(isDialog())
                .perform(click());

        onView(withText("Delete item?"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));

        onView(withText("Delete"))
                .inRoot(isDialog())
                .perform(click());

        onView(withText("Item deleted")).check(matches(isDisplayed()));
        onView(withText("Undo")).perform(click());

        onView(withText("Undo Me")).perform(scrollTo()).check(matches(isDisplayed()));
    }
}
