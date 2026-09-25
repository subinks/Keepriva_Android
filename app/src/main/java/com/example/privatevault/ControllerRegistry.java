package com.example.privatevault;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Owns and deterministically tears down all controllers for one unlocked session. */
final class ControllerRegistry implements VaultController {
    private final List<VaultController> controllers = new ArrayList<>();
    private boolean closed;

    synchronized <T extends VaultController> T register(T controller) {
        Objects.requireNonNull(controller, "controller");
        if (closed) {
            throw new IllegalStateException("Controller registry is already closed");
        }
        controllers.add(controller);
        return controller;
    }

    /**
     * Clears strong references first, then closes in reverse construction order.
     * One faulty controller cannot prevent the remaining controllers from closing.
     */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;

        List<VaultController> closing = new ArrayList<>(controllers);
        controllers.clear();
        RuntimeException firstFailure = null;
        for (int index = closing.size() - 1; index >= 0; index--) {
            try {
                closing.get(index).close();
            } catch (RuntimeException failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        if (firstFailure != null) throw firstFailure;
    }

    synchronized int sizeForTesting() {
        return controllers.size();
    }

    synchronized boolean isClosedForTesting() {
        return closed;
    }
}

