package com.example.privatevault;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
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
    public void toolbarSearch_reachesProtectedRouteAndReturns() {
        createTestVault();

        onView(withContentDescription("Search vault")).perform(click());
        onView(withText("Search route ready")).check(matches(isDisplayed()));
        onView(withText("Back to vault")).perform(click());

        onView(withContentDescription("Search vault")).check(matches(isDisplayed()));
    }

    @Test
    public void emptyCategory_showsDedicatedEmptyState() {
        createTestVault();
        selectHomeCategory("Banking");

        onView(withContentDescription("Empty category Banking"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }

    @Test
    public void categoryNavigation_opensDedicatedCategoryAndShowsDirectEntries() {
        createTestVault();
        createBasicItem("Login Only Item");

        // Saving at the virtual root selects the item's real Login category.
        onView(withContentDescription("Close category Login"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));

        onView(withText("Login Only Item"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));

        // Return to the root: direct Login entries must not leak into the root list.
        onView(withContentDescription("Close category Login"))
                .perform(scrollTo(), performClickDirectly());

        onView(withText("Login Only Item")).check(doesNotExist());

        // Open Login again: its direct entry is restored on the dedicated category screen.
        selectHomeCategory("Login");

        onView(withText("Login Only Item"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }

    @Test
    public void categoryBrowser_doesNotShowItemsFromAnotherCategory() {
        createTestVault();
        createBasicItem("Login Credential");
        onView(withContentDescription("Close category Login"))
                .perform(scrollTo(), performClickDirectly());
        selectHomeCategory("Banking");
        createBasicItem("Banking Credential");

        onView(withText("Banking Credential"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));

        onView(withText("Login Credential")).check(doesNotExist());
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
        onView(withContentDescription("Close category Login"))
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
