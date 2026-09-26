package com.example.privatevault;

/**
 * Invalidates asynchronous UI callbacks when a session is locked or destroyed.
 * A token contains only a monotonically increasing number and no sensitive data.
 */
final class CallbackGeneration {
    private long generation;

    synchronized Token capture() {
        return new Token(generation);
    }

    synchronized boolean isCurrent(Token token) {
        return token != null && token.generation == generation;
    }

    synchronized void invalidate() {
        generation++;
    }

    static final class Token {
        private final long generation;

        private Token(long generation) {
            this.generation = generation;
        }
    }
}
