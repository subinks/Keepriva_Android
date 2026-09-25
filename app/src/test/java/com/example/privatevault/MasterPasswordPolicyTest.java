package com.example.privatevault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class MasterPasswordPolicyTest {
    @Test
    public void nullPassword_isRejected() {
        assertEquals("Use at least 12 characters for a new master password.",
                MasterPasswordPolicy.validate(null));
    }

    @Test
    public void shortPassword_isRejected() {
        assertEquals("Use at least 12 characters for a new master password.",
                MasterPasswordPolicy.validate("Short1!"));
    }

    @Test
    public void twoCharacterClasses_areRejected() {
        assertEquals("Use at least three of: lowercase, uppercase, number, symbol.",
                MasterPasswordPolicy.validate("lowercase12345"));
    }

    @Test
    public void threeCharacterClasses_areAccepted() {
        assertNull(MasterPasswordPolicy.validate("Lowercase12345"));
    }

    @Test
    public void symbolCanSupplyThirdCharacterClass() {
        assertNull(MasterPasswordPolicy.validate("Lowercase-strong!"));
    }
}
