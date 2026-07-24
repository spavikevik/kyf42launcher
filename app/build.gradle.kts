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
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.stefan.kyf42launcher"
        minSdk = 26
        //noinspection ExpiredTargetSdkVersion
        targetSdk = 29
        versionCode = 2
        versionName = "0.2-beta"
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
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
}
