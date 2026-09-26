package com.example.privatevault;

import java.util.Arrays;
import java.util.EnumMap;

/** Session-only sensitive output bytes. Every replacement and exit wipes the previous array. */
final class TransferPayloadStore implements VaultController {
    private final EnumMap<TransferOperation, byte[]> pending =
            new EnumMap<>(TransferOperation.class);
    private boolean closed;

    synchronized void put(TransferOperation operation, byte[] bytes) {
        if (closed) {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
            throw new IllegalStateException("Transfer session is closed");
        }
        clear(operation);
        if (bytes != null) pending.put(operation, bytes);
    }

    synchronized byte[] get(TransferOperation operation) { return pending.get(operation); }

    synchronized void clear(TransferOperation operation) {
        byte[] bytes = pending.remove(operation);
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }

    synchronized void clearAll() {
        for (TransferOperation operation : TransferOperation.values()) clear(operation);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        clearAll();
    }
}
