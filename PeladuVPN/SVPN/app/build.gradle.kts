plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.svpn.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.svpn.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
    // Note: no more `composeOptions { kotlinCompilerExtensionVersion = ... }` —
    // since Kotlin 2.0, the Compose compiler is a Kotlin compiler plugin
    // (applied above) whose version is tied to the Kotlin version instead.
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Real AmneziaWG engine (Apache 2.0) — same API shape as plain WireGuard,
    // but actually implements the Jc/Jmin/Jmax/S1/S2/H1-H4 obfuscation instead
    // of silently ignoring it.
    implementation("com.zaneschepke:amneziawg-android:2.3.7")

    // Xray-core engine for VLESS/VMess/Trojan/Shadowsocks (libv2ray.aar).
    // Safe to leave app/libs/ empty — this just includes nothing until the
    // file is actually placed there (see .github/workflows/build-xray-aar.yml
    // to build a trustworthy copy from the official 2dust/AndroidLibXrayLite
    // source). The com.svpn.app.xray.* code that references the `libv2ray`
    // package WILL fail to compile without it, though — add the .aar first.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
}
