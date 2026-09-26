package com.example.privatevault;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

public class TransferPayloadStoreTest {
    @Test public void replaceCancelAndSessionClearWipeEveryBuffer() {
        TransferPayloadStore store = new TransferPayloadStore();
        byte[] first = {1, 2};
        byte[] second = {3, 4};
        byte[] backup = {5, 6};
        store.put(TransferOperation.ENTRY_EXPORT, first);
        store.put(TransferOperation.ENTRY_EXPORT, second);
        assertArrayEquals(new byte[2], first);
        store.put(TransferOperation.BACKUP_EXPORT, backup);
        store.clear(TransferOperation.ENTRY_EXPORT);
        assertArrayEquals(new byte[2], second);
        assertNull(store.get(TransferOperation.ENTRY_EXPORT));
        store.clearAll();
        assertArrayEquals(new byte[2], backup);
        assertNull(store.get(TransferOperation.BACKUP_EXPORT));
    }

    @Test public void closeWipesAndRejectsLatePayloads() {
        TransferPayloadStore store = new TransferPayloadStore();
        byte[] pending = {7};
        store.put(TransferOperation.TEMPLATE_EXPORT, pending);
        store.close();
        store.close();
        assertArrayEquals(new byte[1], pending);
        byte[] late = {8};
        try {
            store.put(TransferOperation.ENTRY_EXPORT, late);
            fail("Closed store accepted a new payload");
        } catch (IllegalStateException expected) {
            assertArrayEquals(new byte[1], late);
        }
    }
}
