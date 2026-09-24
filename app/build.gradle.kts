plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * THE SIGNING KEY. Do not lose this file.
 *
 * Android only lets a new version install over an old one when both are signed
 * with the same key. Lose it and every customer has to uninstall and reinstall
 * by hand, which is exactly the mess this replaces. It lives in the repository
 * secret KEYSTORE_B64 and is written out by the build workflow; the password is
 * here in the open on purpose, because the file itself is the secret and one
 * secret is one thing to keep safe instead of two.
 *
 * When it is missing - anyone building locally without it - the release build
 * simply comes out unsigned rather than failing.
 */
val keystoreFile = rootProject.file("johnnytv.jks")
val keystoreSecret = "johnnytv-signing"

android {
    // ---- WHITE LABEL: change these two lines per client build ----
    namespace = "com.johnnytv.player"
    // -------------------------------------------------------------

    compileSdk = 35

    defaultConfig {
        applicationId = "com.johnnytv.player"   // WHITE LABEL: unique per client
        minSdk = 26
        targetSdk = 34
        versionCode = 102
        versionName = "5.70"
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystoreSecret
                keyAlias = "johnnytv"
                keyPassword = keystoreSecret
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            if (keystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    implementation("io.coil-kt:coil:2.7.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
