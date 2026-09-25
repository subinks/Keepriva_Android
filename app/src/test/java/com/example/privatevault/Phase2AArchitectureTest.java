package com.example.privatevault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import android.os.Bundle;
import android.os.Parcelable;

import java.io.Serializable;
import java.lang.reflect.Modifier;
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

    @Test
    public void callbackGeneration_invalidatesLateCallbacks() {
        CallbackGeneration generation = new CallbackGeneration();
        CallbackGeneration.Token original = generation.capture();

        assertTrue(generation.isCurrent(original));
        generation.invalidate();

        assertFalse(generation.isCurrent(original));
        assertTrue(generation.isCurrent(generation.capture()));
    }

    @Test
    public void dialogRegistry_dismissesInReverseOrderAndClosesIdempotently() {
        DialogRegistry registry = new DialogRegistry();
        List<String> order = new ArrayList<>();
        FakeDialog first = new FakeDialog("first", order);
        FakeDialog second = new FakeDialog("second", order);
        registry.register(first);
        registry.register(second);

        registry.close();
        registry.close();

        assertEquals(Arrays.asList("second", "first"), order);
        assertEquals(0, registry.sizeForTesting());
        assertTrue(registry.isClosedForTesting());
    }

    @Test
    public void dialogRegistry_rejectsDialogsOpenedByDismissCallbacks() {
        DialogRegistry registry = new DialogRegistry();
        List<String> order = new ArrayList<>();
        FakeDialog late = new FakeDialog("late", order);
        FakeDialog first = new FakeDialog("first", order,
                () -> assertFalse(registry.register(late)));
        registry.register(first);

        registry.dismissAll();

        assertEquals(Arrays.asList("first"), order);
        assertEquals(0, registry.sizeForTesting());
        assertTrue(late.isShowing());
    }

    @Test
    public void actionContracts_declareNoStateFields() {
        Class<?>[] contracts = {
                SetupActions.class,
                UnlockActions.class,
                SecuritySettingsActions.class,
                VaultBrowserActions.class,
                CategoryManagementActions.class,
                ItemDialogActions.class,
                DataTransferActions.class
        };

        for (Class<?> contract : contracts) {
            assertTrue(contract.isInterface());
            assertEquals(contract.getSimpleName() + " must not own state",
                    0, contract.getDeclaredFields().length);
        }
    }

    @Test
    public void controllerInfrastructure_hasNoSecretOrDecryptedModelFields() {
        Class<?>[] infrastructure = {
                ControllerRegistry.class,
                DialogRegistry.class,
                VaultRootRenderer.class,
                VaultViewFactory.class,
                CallbackGeneration.class,
                SetupController.class,
                UnlockController.class,
                SecuritySettingsController.class,
                VaultSecurityPreferences.class
        };

        for (Class<?> type : infrastructure) {
            Arrays.stream(type.getDeclaredFields()).forEach(field -> {
                if (Modifier.isStatic(field.getModifiers())) return;
                assertFalse(type.getSimpleName() + " must not own SecretKey",
                        SecretKey.class.isAssignableFrom(field.getType()));
                assertFalse(type.getSimpleName() + " must not own VaultItem",
                        VaultItem.class.isAssignableFrom(field.getType()));
                assertFalse(type.getSimpleName() + " must not own byte arrays",
                        field.getType().equals(byte[].class));
                assertFalse(type.getSimpleName() + " must not own Bundle state",
                        Bundle.class.isAssignableFrom(field.getType()));
                assertFalse(type.getSimpleName() + " must not own Parcelable state",
                        Parcelable.class.isAssignableFrom(field.getType()));
                assertFalse(type.getSimpleName() + " must not own Serializable state",
                        Serializable.class.isAssignableFrom(field.getType()));
            });
        }
    }

    @Test
    public void waveBControllers_useLifecycleAndTypedHostContracts() {
        Class<?>[] controllers = {
                SetupController.class,
                UnlockController.class,
                SecuritySettingsController.class
        };
        for (Class<?> controller : controllers) {
            assertTrue(controller.getSimpleName() + " must participate in lifecycle teardown",
                    VaultController.class.isAssignableFrom(controller));
        }

        assertTrue(SetupController.Gateway.class.isAssignableFrom(MainActivity.class));
        assertTrue(SetupActions.class.isAssignableFrom(MainActivity.class));
        assertTrue(UnlockController.Gateway.class.isAssignableFrom(MainActivity.class));
        assertTrue(UnlockActions.class.isAssignableFrom(MainActivity.class));
        assertTrue(SecuritySettingsController.Gateway.class.isAssignableFrom(MainActivity.class));
        assertTrue(SecuritySettingsActions.class.isAssignableFrom(MainActivity.class));
    }

    @Test
    public void waveC1Browser_usesLifecycleAndNarrowHostContracts() {
        assertTrue(VaultController.class.isAssignableFrom(LegacyVaultBrowserController.class));
        assertTrue(LegacyVaultBrowserController.DataSource.class.isAssignableFrom(MainActivity.class));
        assertTrue(VaultBrowserActions.class.isAssignableFrom(MainActivity.class));

        Arrays.stream(LegacyVaultBrowserController.class.getDeclaredFields()).forEach(field -> {
            if (Modifier.isStatic(field.getModifiers())) return;
            assertFalse("Browser controller must not own SecretKey",
                    SecretKey.class.isAssignableFrom(field.getType()));
            assertFalse("Browser controller must not own decrypted VaultItem",
                    VaultItem.class.isAssignableFrom(field.getType()));
            assertFalse("Browser controller must not own byte arrays",
                    field.getType().equals(byte[].class));
            assertFalse("Browser controller must not own Bundle state",
                    Bundle.class.isAssignableFrom(field.getType()));
            assertFalse("Browser controller must not own Parcelable state",
                    Parcelable.class.isAssignableFrom(field.getType()));
        });
    }

    private static final class FakeDialog implements DialogRegistry.DialogHandle {
        private final String name;
        private final List<String> order;
        private final Runnable onDismiss;
        private boolean showing = true;

        private FakeDialog(String name, List<String> order) {
            this(name, order, () -> { });
        }

        private FakeDialog(String name, List<String> order, Runnable onDismiss) {
            this.name = name;
            this.order = order;
            this.onDismiss = onDismiss;
        }

        @Override
        public boolean isShowing() {
            return showing;
        }

        @Override
        public void dismiss() {
            showing = false;
            order.add(name);
            onDismiss.run();
        }
    }
}
