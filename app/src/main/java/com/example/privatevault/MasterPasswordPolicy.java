package com.example.privatevault;

/** Pure validation policy shared by setup and password-change UI. */
final class MasterPasswordPolicy {
    private MasterPasswordPolicy() { }

    static String validate(String password) {
        if (password == null || password.length() < 12) {
            return "Use at least 12 characters for a new master password.";
        }
        int classes = 0;
        if (password.matches(".*[a-z].*")) classes++;
        if (password.matches(".*[A-Z].*")) classes++;
        if (password.matches(".*[0-9].*")) classes++;
        if (password.matches(".*[^A-Za-z0-9].*")) classes++;
        if (classes < 3) {
            return "Use at least three of: lowercase, uppercase, number, symbol.";
        }
        return null;
    }
}
