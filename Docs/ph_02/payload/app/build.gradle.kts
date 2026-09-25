import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale

plugins {
    id("com.android.application")
}

fun secret(name: String): String? =
    providers.gradleProperty(name).orNull ?: System.getenv(name)

fun sha256OfSigningCertificate(keystorePath: String, storePassword: String, alias: String): String {
    var lastError: Exception? = null
    for (type in listOf("JKS", "PKCS12")) {
        try {
            val ks = KeyStore.getInstance(type)
            FileInputStream(keystorePath).use { input -> ks.load(input, storePassword.toCharArray()) }
            val cert = ks.getCertificate(alias)
                ?: error("Signing certificate alias '$alias' was not found in the keystore")
            return MessageDigest.getInstance("SHA-256")
                .digest(cert.encoded)
                .joinToString("") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }
        } catch (e: Exception) {
            lastError = e
        }
    }
    throw IllegalStateException("Unable to read signing keystore as JKS or PKCS12", lastError)
}

val pvKeystoreFile = secret("PV_KEYSTORE_FILE")
val pvKeystorePassword = secret("PV_KEYSTORE_PASSWORD")
val pvKeyAlias = secret("PV_KEY_ALIAS")
val pvKeyPassword = secret("PV_KEY_PASSWORD")
val hasReleaseSigningSecrets = listOf(
    pvKeystoreFile,
    pvKeystorePassword,
    pvKeyAlias,
    pvKeyPassword
).all { !it.isNullOrBlank() }

val expectedSigningCertSha256 = if (hasReleaseSigningSecrets) {
    sha256OfSigningCertificate(pvKeystoreFile!!, pvKeystorePassword!!, pvKeyAlias!!)
} else {
    secret("PV_EXPECTED_SIGNING_CERT_SHA256")?.replace(":", "")?.uppercase(Locale.US).orEmpty()
}

android {
    namespace = "com.example.privatevault"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.privatevault"
        minSdk = 28
        targetSdk = 36
        versionCode = 16
        versionName = "2.1.0-alpha1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseSigningSecrets) {
            create("releaseFromSecrets") {
                storeFile = file(pvKeystoreFile!!)
                storePassword = pvKeystorePassword
                keyAlias = pvKeyAlias
                keyPassword = pvKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            buildConfigField("boolean", "RELEASE_HARDENED", "false")
            buildConfigField("String", "EXPECTED_SIGNING_CERT_SHA256", "\"\"")
        }

        release {
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("boolean", "RELEASE_HARDENED", "true")
            buildConfigField("String", "EXPECTED_SIGNING_CERT_SHA256", "\"$expectedSigningCertSha256\"")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigningSecrets) {
                signingConfig = signingConfigs.getByName("releaseFromSecrets")
            }
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }
}

tasks.register("verifyReleaseHardening") {
    group = "verification"
    description = "Checks release hardening prerequisites without exposing signing secrets."
    doLast {
        check(android.buildTypes.getByName("release").isMinifyEnabled) { "Release minification must be enabled." }
        check(android.buildTypes.getByName("release").isShrinkResources) { "Release resource shrinking must be enabled." }
        check(!android.buildTypes.getByName("release").isDebuggable) { "Release must not be debuggable." }
        if (hasReleaseSigningSecrets) {
            check(expectedSigningCertSha256.length == 64) { "Could not derive a valid SHA-256 signing certificate digest." }
            println("Release hardening verified. Signing secrets are present and the certificate digest was derived.")
        } else {
            println("Release hardening verified. No signing secrets supplied to this Gradle invocation; use Android Studio's signed-build wizard or provide PV_* secrets for automated signing.")
        }
    }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.espresso:espresso-intents:3.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

