package com.example.privatevault;

import android.view.View;
import android.widget.FrameLayout;

import java.util.Objects;

/** The only component allowed to replace the child of the Activity-owned root. */
final class VaultRootRenderer {
    private final FrameLayout root;

    VaultRootRenderer(FrameLayout root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    void render(View screen, VaultNavigationState state) {
        Objects.requireNonNull(screen, "screen");
        Objects.requireNonNull(state, "state");
        root.removeAllViews();
        root.addView(screen, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.setContentDescription(state.screen().contentDescription());
    }

    int childCountForTesting() {
        return root.getChildCount();
    }
}

