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

        onView(withContentDescription("Empty vault"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }

    @Test
    public void saveBasicItem_displaysCard() {
        createTestVault();
        createBasicItem("Alpha Item");

        onView(withContentDescription("Open entry Alpha Item")).perform(scrollTo()).check(matches(isDisplayed()));
    }

    @Test
    public void saveLoginItem_displaysCard() {
        createTestVault();
        createLoginItem("Git Login", "user@example.com", "Secret123!");

        onView(withContentDescription("Open entry Git Login")).perform(scrollTo()).check(matches(isDisplayed()));
    }

    @Test
    public void detailsDialog_containsActions() {
        createTestVault();
        createBasicItem("Details Item");

        onView(withContentDescription("Open entry Details Item")).perform(scrollTo(), click());

        onView(withText("Details Item"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));

        onView(withContentDescription("Edit item"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));

        onView(withContentDescription("Delete item"))
                .inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()));

        onView(withContentDescription("Close item details"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));

        onView(withContentDescription("Export entry"))
                .inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }

    @Test
    public void password_isMaskedInitially() {
        createTestVault();
        createLoginItem("Masked Item", "user@test.com", "Secret123!");

        onView(withContentDescription("Open entry Masked Item")).perform(scrollTo(), click());

        onView(withContentDescription("Masked password")).check(matches(isDisplayed()));
        onView(withContentDescription("Show password")).check(matches(isDisplayed()));
        onView(withContentDescription("Copy password securely"))
                .check(matches(isDisplayed()));
    }

    @Test
    public void showAndHidePassword_works() {
        createTestVault();
        createLoginItem("Show Hide", "user@test.com", "Secret123!");

        onView(withContentDescription("Open entry Show Hide")).perform(scrollTo(), click());

        onView(withContentDescription("Show password"))
                .inRoot(isDialog())
                .perform(scrollTo(), click());

        onView(withContentDescription("Visible password"))
                .inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(withText("Secret123!")));

        onView(withContentDescription("Hide password"))
                .inRoot(isDialog())
                .perform(scrollTo(), click());

        onView(withContentDescription("Masked password"))
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

        onView(withContentDescription("Open entry Searchable Credential")).perform(scrollTo()).check(matches(isDisplayed()));
    }

    @Test
    public void searchNoMatch_showsNoMatchingState() {
        createTestVault();
        createBasicItem("Existing Item");

        onView(withHint("Search title, username, phone, website or notes"))
                .perform(scrollTo(), replaceText("does-not-exist"), closeSoftKeyboard());

        onView(withContentDescription("No tree search results"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }

    @Test
    public void categoryNavigation_expandsInlineAndShowsEntries() {
        createTestVault();
        createBasicItem("Login Only Item");

        // Saving the item expands its Login branch.
        onView(withContentDescription("Close category Login"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));

        onView(withText("Login Only Item"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));

        // Collapse Login: its entry must disappear from the tree.
        onView(withContentDescription("Close category Login"))
                .perform(scrollTo(), performClickDirectly());

        onView(withText("Login Only Item")).check(doesNotExist());

        // Expand again: the entry is rendered directly below Login.
        selectHomeCategory("Login");

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

        onView(withContentDescription("Open entry Keep Me")).perform(scrollTo(), click());

        onView(withContentDescription("Delete item"))
                .inRoot(isDialog())
                .perform(scrollTo(), click());

        onView(withText("Delete item?"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));

        onView(withText("Cancel"))
                .inRoot(isDialog())
                .perform(click());

        onView(withContentDescription("Close item details"))
                .inRoot(isDialog())
                .perform(click());

        onView(withContentDescription("Open entry Keep Me"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }

    @Test
    public void deleteThenUndo_restoresItem() {
        createTestVault();
        createBasicItem("Undo Me");

        onView(withContentDescription("Open entry Undo Me")).perform(scrollTo(), click());

        onView(withText("Undo Me"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));

        onView(withContentDescription("Delete item"))
                .inRoot(isDialog())
                .perform(scrollTo(), click());

        onView(withText("Delete item?"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));

        onView(withText("Delete"))
                .inRoot(isDialog())
                .perform(click());

        onView(withText("Item deleted")).check(matches(isDisplayed()));
        onView(withText("Undo")).perform(click());

        onView(withContentDescription("Open entry Undo Me")).perform(scrollTo()).check(matches(isDisplayed()));
    }
}
