package com.example.privatevault;

/**
 * Explicit in-memory destinations for Keepriva's single-activity navigation.
 *
 * Phase 2 renders setup, unlock, loading, error and the existing vault browser.
 * The remaining values reserve stable routes for the later UI phases.
 */
public enum VaultScreen {
    SETUP("Screen setup"),
    UNLOCK("Screen unlock"),
    LOADING("Screen loading"),
    ERROR("Screen error"),
    VAULT_BROWSER("Screen vault browser"),
    SEARCH_RESULTS("Screen search results"),
    ITEM_DETAILS("Screen item details"),
    ITEM_EDITOR("Screen item editor"),
    MOVE_ITEM("Screen move item"),
    ITEM_HISTORY("Screen item history"),
    ITEM_VERSION_DETAILS("Screen item version details");

    private final String contentDescription;

    VaultScreen(String contentDescription) {
        this.contentDescription = contentDescription;
    }

    public String contentDescription() {
        return contentDescription;
    }
}

