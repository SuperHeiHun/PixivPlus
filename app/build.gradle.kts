plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The default app/build directory is held by a stale handle on this machine; use a dedicated output root.
layout.buildDirectory.set(layout.projectDirectory.dir("build-out"))

// Optional release signing material supplied by CI (GitHub Actions secrets).
val keystorePath = providers.environmentVariable("PLP_KEYSTORE_PATH").orNull
val keystoreStorePassword = providers.environmentVariable("PLP_STORE_PASSWORD").orNull
val keystoreKeyAlias = providers.environmentVariable("PLP_KEY_ALIAS").orNull
val keystoreKeyPassword = providers.environmentVariable("PLP_KEY_PASSWORD").orNull

android {
    namespace = "com.heihun.pixivplus"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.heihun.pixivplus"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.0.2"
    }

    signingConfigs {
        if (!keystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystoreStorePassword
                keyAlias = keystoreKeyAlias
                keyPassword = keystoreKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystorePath.isNullOrBlank()) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("release")
            }
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
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.luckypray:dexkit:2.0.6")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
