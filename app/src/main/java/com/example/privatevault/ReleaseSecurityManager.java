package com.example.privatevault;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * Lightweight offline release-integrity checks.
 *
 * These checks raise the cost of casual repackaging but are not a substitute for
 * cryptographic data protection: an attacker who can modify and resign an APK can
 * also attempt to patch client-side checks. The vault remains protected primarily
 * by its encryption and authentication design.
 */
public final class ReleaseSecurityManager {
    private ReleaseSecurityManager() {}

    public static Result verify(Context context) {
        if (!BuildConfig.RELEASE_HARDENED) return Result.ok();

        ApplicationInfo info = context.getApplicationInfo();
        if ((info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            return Result.fail("This release is unexpectedly debuggable and will not open the vault.");
        }

        String expected = normalize(BuildConfig.EXPECTED_SIGNING_CERT_SHA256);
        if (expected.isEmpty()) {
            // A release built through Android Studio's signing wizard may not have
            // a compile-time certificate pin. Release build flags still apply.
            return Result.ok();
        }

        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pkg = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo signingInfo = pkg.signingInfo;
            if (signingInfo == null) return Result.fail("The application signing identity could not be verified.");

            Signature[] signers = signingInfo.hasMultipleSigners()
                    ? signingInfo.getApkContentsSigners()
                    : signingInfo.getSigningCertificateHistory();
            if (signers == null || signers.length == 0) {
                return Result.fail("The application signing identity could not be verified.");
            }

            for (Signature signer : signers) {
                if (expected.equals(sha256(signer.toByteArray()))) return Result.ok();
            }
            return Result.fail("The application signature does not match the trusted release signing certificate.");
        } catch (Exception e) {
            return Result.fail("The application signing identity could not be verified.");
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) out.append(String.format(Locale.US, "%02X", b));
        return out.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace(":", "").trim().toUpperCase(Locale.US);
    }

    public static final class Result {
        public final boolean ok;
        public final String message;

        private Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }

        static Result ok() { return new Result(true, ""); }
        static Result fail(String message) { return new Result(false, message); }
    }
}
