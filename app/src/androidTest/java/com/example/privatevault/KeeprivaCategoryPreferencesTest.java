package com.example.privatevault;

import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;`nimport static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
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

        onView(withText("Login")).check(matches(isDisplayed()));
        onView(withText("Banking")).check(matches(isDisplayed()));
        onView(withText("Secure Note")).check(matches(isDisplayed()));
    }

    @Test
    public void newFolderCategory_withoutFields_isAllowed() {
        createTestVault();
        createFolderCategory("Folder Only");

        onView(withContentDescription("Manage categories")).perform(scrollTo(), click());
        onView(withText("Folder Only  •  0 fields")).check(matches(isDisplayed()));
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
        onView(withText("Membership  •  2 fields")).check(matches(isDisplayed()));
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
    public void customCategoryAppearsInHomeFilter() {
        createTestVault();
        createFolderCategory("Filter Category");

        onView(withContentDescription("Open category Filter Category"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }
}
