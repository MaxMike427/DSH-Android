plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dsh.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dshapp"
        minSdk = 26
        // Android 10+ blocks exec() of app-private files for targetSdk >= 29.
        // PRoot must execute binaries from app storage, so the target stays at 28.
        targetSdk = 28
        // Standard semantic version: 0.1.<build number>. A commit hash was
        // unreadable on the device and is not a version.
        val buildId = (System.getenv("DSH_BUILD_ID") ?: "0").toLongOrNull() ?: 0L
        val patch = when {
            buildId > 0 -> (buildId % 1000).toInt()
            else -> (System.currentTimeMillis() / 60_000L % 1000).toInt()
        }
        versionName = "0.1.$patch"
        // versionCode must be a positive 32-bit int and increase per build.
        versionCode = when {
            buildId in 1..2_000_000_000L -> buildId.toInt()
            else -> (System.currentTimeMillis() / 60_000L).rem(2_000_000_000L).toInt()
        }

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // A committed keystore, on purpose.
    //
    // CI generates a fresh debug key per run, which makes every build a
    // different signer: installing a new APK over an old one then fails with
    // INSTALL_FAILED_UPDATE_INCOMPATIBLE and the only way forward is an
    // uninstall, which throws away the extracted Ubuntu rootfs. A stable key
    // lets an update install in place and keep the runtime.
    //
    // The password is public because the key protects nothing: it exists so
    // consecutive local builds, not to authenticate a publisher. There is no
    // Play Store listing and no secret to defend.
    signingConfigs {
        create("stable") {
            storeFile = rootProject.file("keystore/dsh.jks")
            storePassword = "dshandroid"
            keyAlias = "dsh"
            keyPassword = "dshandroid"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(21)
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf("META-INF/*.kotlin_module")
        }
    }

    androidResources {
        // Runtime payload archives are already compressed; re-compressing them
        // wastes install time and shrinks nothing.
        noCompress += listOf("zst", "xz", "gz")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.webkit:webkit:1.15.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Pure-Java XZ decoder for the rootfs payload (no NDK or native library).
    implementation("org.tukaani:xz:1.10")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}

