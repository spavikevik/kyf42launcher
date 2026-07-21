import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing is read from keystore.properties at the project root (gitignored).
// Generate the keystore + this file once before the first release build — see
// RELEASE.md. When absent, assembleRelease still runs but produces an unsigned APK.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "dev.stefan.kyf42launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.stefan.kyf42launcher"
        // The KYF42 runs Android 10; status-bar code calls API 29 (e.g.
        // NetworkCapabilities#getSignalStrength) unguarded, so 29 is the floor.
        minSdk = 29
        //noinspection ExpiredTargetSdkVersion
        targetSdk = 29
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed when keystore.properties is present; unsigned otherwise.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // 1.19.0 requires compileSdk 37, beyond AGP 9.0's supported max of 36.
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}
