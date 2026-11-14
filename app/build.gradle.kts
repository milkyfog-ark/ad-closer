import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Fixed signing key (standard Android debug credentials, generated for this
// project and stored base64-encoded in the repo as debug-keystore.b64).
// GitHub Actions runners would otherwise create a fresh debug key on every
// run, and Android refuses to update an app whose signature changed —
// that is exactly the "package conflicts with existing package" error the
// user hit when trying to install v0.2 over v0.1. Decoding the same key on
// every build keeps the signature stable so future versions install as
// plain updates.
val fixedKeystore: File = rootProject.file("build/fixed-debug.keystore").apply {
    if (!exists()) {
        parentFile.mkdirs()
        val b64 = rootProject.file("debug-keystore.b64").readText()
        writeBytes(Base64.getMimeDecoder().decode(b64))
    }
}

android {
    namespace = "jp.milkyfog.adcloser"
    compileSdk = 34

    defaultConfig {
        applicationId = "jp.milkyfog.adcloser"
        minSdk = 26
        targetSdk = 34
        versionCode = 59
        versionName = "2.38.0"
    }

    signingConfigs {
        create("fixed") {
            storeFile = fixedKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
