package com.example.privatevault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public class Phase2AArchitectureTest {

    @Test
    public void vaultSession_startsLockedAndRejectsKeyAccess() {
        VaultSessionCoordinator session = new VaultSessionCoordinator();

        assertFalse(session.isUnlocked());
        try {
            session.requireKey();
            fail("A locked session must not expose a key");
        } catch (IllegalStateException expected) {
            assertEquals("Vault session is locked", expected.getMessage());
        }
    }

    @Test
    public void vaultSession_unlockAndClearOwnTheOnlySessionReference() {
        VaultSessionCoordinator session = new VaultSessionCoordinator();
        SecretKey key = new SecretKeySpec(new byte[32], "AES");

        session.unlock(key);
        assertTrue(session.isUnlocked());
        assertSame(key, session.requireKey());

        session.clear();
        session.clear();
        assertFalse(session.isUnlocked());
    }

    @Test
    public void controllerRegistry_closesInReverseOrderAndDropsReferences() {
        ControllerRegistry registry = new ControllerRegistry();
        List<String> order = new ArrayList<>();
        registry.register(() -> order.add("first"));
        registry.register(() -> order.add("second"));

        assertEquals(2, registry.sizeForTesting());
        registry.close();

        assertEquals(Arrays.asList("second", "first"), order);
        assertEquals(0, registry.sizeForTesting());
        assertTrue(registry.isClosedForTesting());
    }

    @Test
    public void controllerRegistry_closeIsIdempotent() {
        ControllerRegistry registry = new ControllerRegistry();
        int[] calls = {0};
        registry.register(() -> calls[0]++);

        registry.close();
        registry.close();

        assertEquals(1, calls[0]);
    }

    @Test
    public void controllerRegistry_continuesTeardownAfterFailure() {
        ControllerRegistry registry = new ControllerRegistry();
        List<String> order = new ArrayList<>();
        registry.register(() -> order.add("first"));
        registry.register(() -> {
            order.add("failing");
            throw new IllegalStateException("expected test failure");
        });
        registry.register(() -> order.add("last"));

        try {
            registry.close();
            fail("The first close failure must be reported after complete teardown");
        } catch (IllegalStateException expected) {
            assertEquals("expected test failure", expected.getMessage());
        }

        assertEquals(Arrays.asList("last", "failing", "first"), order);
        assertEquals(0, registry.sizeForTesting());
    }
}

