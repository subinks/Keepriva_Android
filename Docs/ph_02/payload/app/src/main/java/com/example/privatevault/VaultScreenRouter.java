package com.example.privatevault;

import java.util.ArrayDeque;
import java.util.Deque;

/** In-memory navigation stack. Nothing in this router is persisted across recreation. */
public final class VaultScreenRouter {
    private final Deque<VaultNavigationState> backStack = new ArrayDeque<>();
    private VaultNavigationState currentState;

    public synchronized void reset(VaultNavigationState rootState) {
        if (rootState == null) throw new IllegalArgumentException("rootState is required");
        backStack.clear();
        currentState = rootState;
    }

    public synchronized void navigate(VaultNavigationState nextState) {
        if (nextState == null) throw new IllegalArgumentException("nextState is required");
        if (currentState != null) backStack.push(currentState);
        currentState = nextState;
    }

    public synchronized void replaceCurrent(VaultNavigationState replacement) {
        if (replacement == null) throw new IllegalArgumentException("replacement is required");
        currentState = replacement;
    }

    public synchronized boolean canGoBack() {
        return !backStack.isEmpty();
    }

    public synchronized VaultNavigationState goBack() {
        if (backStack.isEmpty()) return currentState;
        currentState = backStack.pop();
        return currentState;
    }

    public synchronized void clear() {
        backStack.clear();
        currentState = null;
    }

    public synchronized VaultNavigationState currentState() {
        return currentState;
    }

    public synchronized int backStackSize() {
        return backStack.size();
    }
}

