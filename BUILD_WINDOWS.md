# Build and install on Windows 11

## 1. Install Android Studio
Install a current stable Android Studio and make sure Android SDK Platform 36 and SDK Build Tools 36.0.0 are installed.

## 2. Open the project
1. Start Android Studio.
2. Choose **Open**.
3. Select the extracted `PrivateVaultAndroid` folder.
4. Let Gradle sync finish.

## 3. Build a debug APK
Use **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.

Expected output:
`app\\build\\outputs\\apk\\debug\\app-debug.apk`

## 4. Install on your Android phone
### Option A - copy APK
Copy `app-debug.apk` to the phone, open it, and allow installation from that file manager when Android asks.

### Option B - USB / adb
Enable Developer options + USB debugging, connect the phone, then run:

```bat
adb install -r app\\build\\outputs\\apk\\debug\\app-debug.apk
```

## 5. Build a private signed APK
For long-term personal use, create your own signing key:

1. **Build > Generate Signed App Bundle / APK**.
2. Select **APK**.
3. Choose **Create new...** for the keystore.
4. Store the `.jks` file and its password securely and offline.
5. Select the **release** variant and build.

Do not lose the signing key if you want to install future upgrades over the existing app without uninstalling it.
