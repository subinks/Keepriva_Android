package com.example.privatevault;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.RootMatchers.isPlatformPopup;
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
public class KeeprivaCategoryDeletionTest extends KeeprivaTestBase {

    @Test
    public void lockControl_isAccessibleIconAndLocksVault() {
        createTestVault();
        onView(withContentDescription("Lock vault"))
                .check(matches(isDisplayed()))
                .perform(click());
        onView(withText("Unlock")).check(matches(isDisplayed()));
    }

    @Test
    public void deletingEmptyBuiltInCategory_hidesItPersistently() {
        createTestVault();
        openCategoryManager();
        onView(withContentDescription("Delete category Contact"))
                .inRoot(isDialog()).perform(scrollTo(), click());
        onView(withContentDescription("Confirm delete category Contact"))
                .inRoot(isDialog()).perform(click());

        onView(withContentDescription("Open category Contact")).check(doesNotExist());

        scenario.recreate();
        onView(withContentDescription("Open category Contact")).check(doesNotExist());
    }

    @Test
    public void cancelForceDelete_keepsCategory() {
        createTestVault();
        createFolderCategory("Keep Cascade");
        createSubcategory("Keep Cascade", "Keep Child");

        openCategoryManager();
        onView(withContentDescription("Delete category Keep Cascade"))
                .inRoot(isDialog()).perform(scrollTo(), click());
        onView(withContentDescription("Delete category and all contents"))
                .inRoot(isDialog()).perform(click());
        onView(withContentDescription("Cancel permanent category deletion"))
                .inRoot(isDialog()).perform(click());
        waitForHomeScreen();

        openCategoryManager();
        onView(withContentDescription("Delete category Keep Cascade"))
                .inRoot(isDialog()).perform(scrollTo()).check(matches(isDisplayed()));
    }

    @Test
    public void forceDelete_removesCompleteSubtreeAndItems() {
        createTestVault();
        createFolderCategory("Cascade Root");
        createSubcategory("Cascade Root", "Cascade Child");
        createEntryInCategory("Cascade Child", "Cascade Secret");

        openCategoryManager();
        onView(withContentDescription("Delete category Cascade Root"))
                .inRoot(isDialog()).perform(scrollTo(), click());
        onView(withContentDescription("Delete category and all contents"))
                .inRoot(isDialog()).perform(click());
        onView(withContentDescription("Confirm permanent category deletion"))
                .inRoot(isDialog()).perform(click());
        waitForHomeScreen();

        onView(withContentDescription("Open category Cascade Root")).check(doesNotExist());
        onView(withContentDescription("Open category Cascade Child")).check(doesNotExist());
        onView(withContentDescription("Open entry Cascade Secret")).check(doesNotExist());
    }

    @Test
    public void forceDeleteBuiltIn_removesDescendantsItemsAndPersistsTombstone() {
        createTestVault();
        createSubcategory("Login", "Login Cascade Child");
        createEntryInCategory("Login Cascade Child", "Built In Cascade Secret");

        openCategoryManager();
        onView(withContentDescription("Delete category Login"))
                .inRoot(isDialog()).perform(scrollTo(), click());
        onView(withContentDescription("Delete category and all contents"))
                .inRoot(isDialog()).perform(click());
        onView(withContentDescription("Confirm permanent category deletion"))
                .inRoot(isDialog()).perform(click());
        waitForHomeScreen();

        onView(withContentDescription("Open category Login")).check(doesNotExist());
        onView(withHint("Search title, username, phone, website or notes"))
                .perform(replaceText("Built In Cascade Secret"), closeSoftKeyboard());
        onView(withContentDescription("Open entry Built In Cascade Secret"))
                .check(doesNotExist());

        scenario.recreate();
        onView(withContentDescription("Open category Login")).check(doesNotExist());
    }

    private void openCategoryManager() {
        onView(withContentDescription("Manage categories")).perform(scrollTo(), click());
        onView(withContentDescription("Close category manager"))
                .inRoot(isDialog()).check(matches(isDisplayed()));
    }

    private void createSubcategory(String parent, String child) {
        selectHomeCategory(parent);
        onView(withContentDescription("Add entry or subcategory"))
                .perform(scrollTo(), performClickDirectly());
        onView(withText("Sub Category"))
                .inRoot(isPlatformPopup())
                .perform(click());
        onView(withHint("Category name"))
                .perform(replaceText(child), closeSoftKeyboard());
        onView(withText("Save")).perform(click());
        onView(withContentDescription("Open category " + child))
                .perform(scrollTo()).check(matches(isDisplayed()));
    }

    private void createEntryInCategory(String category, String title) {
        selectHomeCategory(category);
        onView(withContentDescription("Add entry or subcategory"))
                .perform(scrollTo(), performClickDirectly());
        onView(withText("Entry"))
                .inRoot(isPlatformPopup())
                .perform(click());
        onView(withContentDescription("Item title"))
                .perform(replaceText(title), closeSoftKeyboard());
        onView(withText("Save")).perform(click());
        onView(withContentDescription("Open entry " + title))
                .perform(scrollTo()).check(matches(isDisplayed()));
    }
}