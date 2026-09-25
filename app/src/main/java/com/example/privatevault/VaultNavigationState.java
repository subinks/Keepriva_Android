package com.example.privatevault;

import java.util.Objects;

/**
 * Immutable, process-memory-only navigation metadata.
 *
 * This class must never contain a SecretKey, password, decrypted item, custom-field
 * map, Android Bundle, Parcelable, or Serializable implementation. Activity
 * recreation deliberately starts from setup/unlock instead of restoring this state.
 */
public final class VaultNavigationState {
    public static final long NO_ID = -1L;

    private final VaultScreen screen;
    private final String currentCategory;
    private final String searchQuery;
    private final long selectedItemId;
    private final long selectedHistoryVersionId;
    private final int listScrollPosition;

    private VaultNavigationState(
            VaultScreen screen,
            String currentCategory,
            String searchQuery,
            long selectedItemId,
            long selectedHistoryVersionId,
            int listScrollPosition) {
        this.screen = Objects.requireNonNull(screen, "screen");
        this.currentCategory = safe(currentCategory);
        this.searchQuery = safe(searchQuery);
        this.selectedItemId = selectedItemId;
        this.selectedHistoryVersionId = selectedHistoryVersionId;
        this.listScrollPosition = Math.max(0, listScrollPosition);
    }

    public static VaultNavigationState root(VaultScreen screen) {
        return new VaultNavigationState(screen, "All", "", NO_ID, NO_ID, 0);
    }

    public static VaultNavigationState vaultBrowser(
            String currentCategory,
            String searchQuery,
            int listScrollPosition) {
        return new VaultNavigationState(
                VaultScreen.VAULT_BROWSER,
                currentCategory,
                searchQuery,
                NO_ID,
                NO_ID,
                listScrollPosition);
    }

    public VaultNavigationState forScreen(VaultScreen destination) {
        return new VaultNavigationState(
                destination,
                currentCategory,
                searchQuery,
                selectedItemId,
                selectedHistoryVersionId,
                listScrollPosition);
    }

    public VaultNavigationState withCategory(String category) {
        return new VaultNavigationState(
                screen,
                category,
                searchQuery,
                selectedItemId,
                selectedHistoryVersionId,
                listScrollPosition);
    }

    public VaultNavigationState withSearchQuery(String query) {
        return new VaultNavigationState(
                screen,
                currentCategory,
                query,
                selectedItemId,
                selectedHistoryVersionId,
                listScrollPosition);
    }

    public VaultNavigationState withSelectedItemId(long itemId) {
        return new VaultNavigationState(
                screen,
                currentCategory,
                searchQuery,
                itemId,
                selectedHistoryVersionId,
                listScrollPosition);
    }

    public VaultNavigationState withSelectedHistoryVersionId(long versionId) {
        return new VaultNavigationState(
                screen,
                currentCategory,
                searchQuery,
                selectedItemId,
                versionId,
                listScrollPosition);
    }

    public VaultNavigationState withListScrollPosition(int position) {
        return new VaultNavigationState(
                screen,
                currentCategory,
                searchQuery,
                selectedItemId,
                selectedHistoryVersionId,
                position);
    }

    public VaultScreen screen() {
        return screen;
    }

    public String currentCategory() {
        return currentCategory;
    }

    public String searchQuery() {
        return searchQuery;
    }

    public long selectedItemId() {
        return selectedItemId;
    }

    public long selectedHistoryVersionId() {
        return selectedHistoryVersionId;
    }

    public int listScrollPosition() {
        return listScrollPosition;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof VaultNavigationState)) return false;
        VaultNavigationState that = (VaultNavigationState) other;
        return selectedItemId == that.selectedItemId
                && selectedHistoryVersionId == that.selectedHistoryVersionId
                && listScrollPosition == that.listScrollPosition
                && screen == that.screen
                && currentCategory.equals(that.currentCategory)
                && searchQuery.equals(that.searchQuery);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                screen,
                currentCategory,
                searchQuery,
                selectedItemId,
                selectedHistoryVersionId,
                listScrollPosition);
    }
}

