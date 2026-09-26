package com.example.privatevault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

import org.junit.Test;

public class ItemDialogControllerTest {
    @Test
    public void undoSnapshotCopiesFieldsAndRestoresAsNewRow() {
        VaultItem original = new VaultItem();
        original.id = 17L;
        original.title = "Test record";
        original.category = "Login";
        original.username = "user";
        original.password = "private";
        original.website = "example";
        original.websiteUrl = "https://example.test";
        original.phone1 = "111";
        original.phone2 = "222";
        original.phone3 = "333";
        original.notes = "note";
        original.createdAt = 1234L;
        original.customFields.put("field", "value");

        VaultItem snapshot = ItemDialogController.copyVaultItem(original);
        assertNotSame(original, snapshot);
        assertEquals(0L, snapshot.id);
        assertEquals(original.createdAt, snapshot.createdAt);
        assertEquals(original.title, snapshot.title);
        assertEquals(original.category, snapshot.category);
        assertEquals(original.username, snapshot.username);
        assertEquals(original.password, snapshot.password);
        assertEquals(original.website, snapshot.website);
        assertEquals(original.websiteUrl, snapshot.websiteUrl);
        assertEquals(original.phone1, snapshot.phone1);
        assertEquals(original.phone2, snapshot.phone2);
        assertEquals(original.phone3, snapshot.phone3);
        assertEquals(original.notes, snapshot.notes);
        assertEquals(original.customFields, snapshot.customFields);
        assertNotSame(original.customFields, snapshot.customFields);
        original.customFields.put("field", "changed");
        assertEquals("value", snapshot.customFields.get("field"));
    }
}
