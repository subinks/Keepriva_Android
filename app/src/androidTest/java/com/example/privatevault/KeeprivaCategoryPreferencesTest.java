package com.example.privatevault;

import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.hasSibling;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.is;

import android.widget.Spinner;

import androidx.test.espresso.matcher.ViewMatchers;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class KeeprivaCategoryPreferencesTest extends KeeprivaTestBase {

    @Test
    public void categoriesDialog_listsBuiltIns() {
        createTestVault();
        onView(withContentDescription("Manage categories")).perform(scrollTo(), click());

        onView(withText("Login"))
                .inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()));
        onView(withText("Banking"))
                .inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()));
        onView(withText("Secure Note"))
                .inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }

    @Test
    public void newFolderCategory_withoutFields_isAllowed() {
        createTestVault();
        createFolderCategory("Folder Only");

        onView(withContentDescription("Manage categories")).perform(scrollTo(), click());
        onView(withText("Folder Only  •  0 fields"))
                .inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }

    @Test
    public void duplicateCategoryName_isRejected() {
        createTestVault();
        createFolderCategory("Duplicate Test");

        onView(withContentDescription("Manage categories")).perform(scrollTo(), click());
        onView(withText("+  New category / subcategory")).perform(scrollTo(), click());

        onView(withHint("Category name"))
                .perform(replaceText("Duplicate Test"), closeSoftKeyboard());
        onView(withText("Save")).perform(click());

        onView(withText("Save")).check(matches(isDisplayed()));
    }

    @Test
    public void builtInCategoryName_isRejected() {
        createTestVault();

        onView(withContentDescription("Manage categories")).perform(scrollTo(), click());
        onView(withText("+  New category / subcategory")).perform(scrollTo(), click());

        onView(withHint("Category name"))
                .perform(replaceText("Login"), closeSoftKeyboard());
        onView(withText("Save")).perform(click());

        onView(withText("Save")).check(matches(isDisplayed()));
    }

    @Test
    public void categoryWithCustomFields_canBeCreated() {
        createTestVault();

        onView(withContentDescription("Manage categories")).perform(scrollTo(), click());
        onView(withText("+  New category / subcategory")).perform(scrollTo(), click());

        onView(withHint("Category name"))
                .perform(replaceText("Membership"), closeSoftKeyboard());

        onView(withHint("Field names - one per line (optional for folder categories)"))
                .perform(replaceText("Member ID\nPIN"), closeSoftKeyboard());

        onView(withHint("Sensitive field names - one per line (optional)"))
                .perform(replaceText("PIN"), closeSoftKeyboard());

        onView(withText("Save")).perform(click());

        onView(withContentDescription("Manage categories")).perform(scrollTo(), click());
        onView(withText("Membership  •  2 fields"))
                .inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }

    @Test
    public void preferencesDialog_showsDefaultDepth() {
        createTestVault();
        onView(withText("Preferences")).perform(scrollTo(), click());

        onView(withText("Enable editing category nesting depth"))
                .check(matches(isDisplayed()));
        onView(withHint("Maximum category depth (1-5)"))
                .check(matches(isDisplayed()));
    }

    @Test
    public void preferencesRejectsDepthZero() {
        createTestVault();
        onView(withText("Preferences")).perform(scrollTo(), click());

        onView(withText("Enable editing category nesting depth")).perform(click());
        onView(withHint("Maximum category depth (1-5)"))
                .perform(replaceText("0"), closeSoftKeyboard());
        onView(withText("Save")).perform(click());

        onView(withText("Save")).check(matches(isDisplayed()));
    }

    @Test
    public void preferencesRejectsDepthAboveFive() {
        createTestVault();
        onView(withText("Preferences")).perform(scrollTo(), click());

        onView(withText("Enable editing category nesting depth")).perform(click());
        onView(withHint("Maximum category depth (1-5)"))
                .perform(replaceText("6"), closeSoftKeyboard());
        onView(withText("Save")).perform(click());

        onView(withText("Save")).check(matches(isDisplayed()));
    }

    @Test
    public void preferencesAcceptsDepthFive() {
        createTestVault();
        onView(withText("Preferences")).perform(scrollTo(), click());

        onView(withText("Enable editing category nesting depth")).perform(click());
        onView(withHint("Maximum category depth (1-5)"))
                .perform(replaceText("5"), closeSoftKeyboard());
        onView(withText("Save")).perform(click());

        onView(withHint("Search title, username, phone, website or notes"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }

    @Test
    public void categoryTree_countReflectsStoredEntryAfterHomeRebuild() {
        createTestVault();
        createBasicItem("Counted Login Item");

        /*
         * Rebuild the home screen through the real lock/unlock flow.
         * Patch 18 loads entries before constructing the category tree, so the
         * Login row must now be created with the persisted item count.
         */
        lockVault();
        unlockWithTestPassword();

        onView(withContentDescription("Open category Login"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));

        onView(allOf(
                withText("Category • 1 entry"),
                hasSibling(withText("Login"))))
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }
    @Test
    public void builtInCategories_haveDistinctSemanticIcons() {
        createTestVault();
        onView(withContentDescription("Category icon Login")).perform(scrollTo()).check(matches(isDisplayed()));
        onView(withContentDescription("Category icon Website")).perform(scrollTo()).check(matches(isDisplayed()));
        onView(withContentDescription("Category icon App")).perform(scrollTo()).check(matches(isDisplayed()));
        onView(withContentDescription("Category icon Contact")).perform(scrollTo()).check(matches(isDisplayed()));
        onView(withContentDescription("Category icon Banking")).perform(scrollTo()).check(matches(isDisplayed()));
        onView(withContentDescription("Category icon Work")).perform(scrollTo()).check(matches(isDisplayed()));
        onView(withContentDescription("Category icon Personal")).perform(scrollTo()).check(matches(isDisplayed()));
        onView(withContentDescription("Category icon Secure Note")).perform(scrollTo()).check(matches(isDisplayed()));
        onView(withContentDescription("Category icon Other")).perform(scrollTo()).check(matches(isDisplayed()));
    }

    @Test
    public void customCategory_usesCommonCustomIcon() {
        createTestVault();
        createFolderCategory("Custom Icon Test");
        onView(withContentDescription("Category icon Custom")).perform(scrollTo()).check(matches(isDisplayed()));
    }

    @Test
    public void subCategory_inheritsBuiltInParentIcon() {
        createTestVault();
        selectHomeCategory("Login");

        onView(withContentDescription("Add entry or subcategory"))
                .perform(scrollTo(), click());
        onView(withText("Sub Category")).perform(click());

        onView(withHint("Category name"))
                .perform(replaceText("Login Child"), closeSoftKeyboard());
        onView(withText("Save")).perform(click());

        /*
         * Verify the icon inside the Login Child row itself.
         *
         * Do not use withIndex(..., 1): Espresso can evaluate a stateful indexed
         * matcher more than once while resolving/scrolling, which made this test
         * flaky even though the child row was present.
         */
        onView(allOf(
                withContentDescription("Open category Login Child"),
                hasDescendant(withContentDescription("Category icon Login"))))
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }

    @Test
    public void customCategoryActions_areCompactIconButtons() {
        createTestVault();
        createFolderCategory("Action Icon Test");

        onView(withContentDescription("Manage categories"))
                .perform(scrollTo(), click());
        onView(withContentDescription("Edit category Action Icon Test"))
                .inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()));
        onView(withContentDescription("Delete category Action Icon Test"))
                .inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }
    @Test
    public void quickAddMenu_showsEntryAndSubCategoryOptions() {
        createTestVault();

        onView(withContentDescription("Add entry or subcategory"))
                .perform(scrollTo(), click());

        onView(withText("Entry")).check(matches(isDisplayed()));
        onView(withText("Sub Category")).check(matches(isDisplayed()));
    }

    @Test
    public void searchForSubCategory_showsTreePath() {
        createTestVault();
        createFolderCategory("Search Child");

        onView(withHint("Search title, username, phone, website or notes"))
                .perform(scrollTo(), replaceText("Search Child"), closeSoftKeyboard());

        onView(withContentDescription("Close category Search Child"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }
    @Test
    public void customCategoryAppearsInHomeFilter() {
        createTestVault();
        createFolderCategory("Filter Category");

        onView(withContentDescription("Open category Filter Category"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }
}
