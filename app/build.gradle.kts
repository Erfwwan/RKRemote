plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release credentials are supplied by the operator through environment variables.
// Never store a signing key or passwords in the repository.
val releaseStoreFile = providers.environmentVariable("RKREMOTE_RELEASE_STORE_FILE").orNull?.takeIf { it.isNotBlank() }
val releaseStorePassword = providers.environmentVariable("RKREMOTE_RELEASE_STORE_PASSWORD").orNull?.takeIf { it.isNotBlank() }
val releaseKeyAlias = providers.environmentVariable("RKREMOTE_RELEASE_KEY_ALIAS").orNull?.takeIf { it.isNotBlank() }
val releaseKeyPassword = providers.environmentVariable("RKREMOTE_RELEASE_KEY_PASSWORD").orNull?.takeIf { it.isNotBlank() }
val releaseSigningValues = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
val hasReleaseSigning = releaseSigningValues.all { it != null }
require(releaseSigningValues.all { it == null } || hasReleaseSigning) {
    "Release signing requires all four RKREMOTE_RELEASE_* environment variables."
}
if (hasReleaseSigning) {
    require(file(releaseStoreFile!!).isFile) {
        "RKREMOTE_RELEASE_STORE_FILE does not point to an existing keystore file."
    }
}

android {
    namespace = "com.rkremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rkremote"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword!!
                keyAlias = releaseKeyAlias!!
                keyPassword = releaseKeyPassword!!
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Noise XX handshake engine; secure P2P is runtime-gated to Android 13+ (API 33).
    implementation("com.stonygrey:torann:0.3.1")
    // Native WebRTC engine for ICE, DTLS-SRTP and ordered data channels.
    implementation("io.github.webrtc-sdk:android:150.7871.04")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
