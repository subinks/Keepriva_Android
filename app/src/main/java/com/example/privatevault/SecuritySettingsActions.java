package com.example.privatevault;

/** Navigation-only events emitted by security settings. */
interface SecuritySettingsActions {
    void onSecuritySettingsClosed();
    void onLockRequested();
}
