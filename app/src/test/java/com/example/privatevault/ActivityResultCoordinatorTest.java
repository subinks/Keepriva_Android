package com.example.privatevault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ActivityResultCoordinatorTest {
    @Test public void requestCodesPreserveLegacyPickerMapping() {
        assertEquals(7001, ActivityResultCoordinator.requestCode(TransferOperation.ENTRY_EXPORT));
        assertEquals(7002, ActivityResultCoordinator.requestCode(TransferOperation.TEMPLATE_EXPORT));
        assertEquals(7003, ActivityResultCoordinator.requestCode(TransferOperation.JSON_IMPORT));
        assertEquals(7004, ActivityResultCoordinator.requestCode(TransferOperation.BACKUP_EXPORT));
        assertEquals(7005, ActivityResultCoordinator.requestCode(TransferOperation.BACKUP_RESTORE));
    }

    @Test public void wrongRequestCannotConsumeActivePicker() {
        ActivityResultCoordinator coordinator = new ActivityResultCoordinator();
        coordinator.begin(TransferOperation.JSON_IMPORT, 100L);
        assertNull(coordinator.complete(7004, 110L, 50L, true));
        assertTrue(coordinator.isPickerInProgress());
        ActivityResultCoordinator.Completed result = coordinator.complete(7003, 110L, 50L, true);
        assertEquals(TransferOperation.JSON_IMPORT, result.operation);
        assertFalse(result.lockAfterPicker);
        assertFalse(coordinator.isPickerInProgress());
    }

    @Test public void timeoutAndImmediateLockAreReportedBeforeResultHandling() {
        ActivityResultCoordinator coordinator = new ActivityResultCoordinator();
        coordinator.begin(TransferOperation.BACKUP_RESTORE, 100L);
        assertTrue(coordinator.complete(7005, 151L, 50L, true).lockAfterPicker);
        coordinator.begin(TransferOperation.ENTRY_EXPORT, 200L);
        assertTrue(coordinator.complete(7001, 201L,
                VaultSecurityPreferences.AUTO_LOCK_IMMEDIATELY, true).lockAfterPicker);
        coordinator.begin(TransferOperation.JSON_IMPORT, 300L);
        assertFalse(coordinator.complete(7003, 500L, 50L, false).lockAfterPicker);
    }

    @Test public void clearForgetsPicker() {
        ActivityResultCoordinator coordinator = new ActivityResultCoordinator();
        coordinator.begin(TransferOperation.BACKUP_EXPORT, 100L);
        coordinator.clear();
        assertFalse(coordinator.isPickerInProgress());
        assertNull(coordinator.complete(7004, 120L, 50L, true));
    }
}
