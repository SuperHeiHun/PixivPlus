import java.nio.ByteBuffer
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The default app/build directory is held by a stale handle on this machine; use a dedicated output root.
layout.buildDirectory.set(layout.projectDirectory.dir(providers.gradleProperty("plp.buildDirectory").getOrElse("build-out")))

// Optional release signing material supplied by CI (GitHub Actions secrets).
val keystorePath = providers.environmentVariable("PLP_KEYSTORE_PATH").orNull
val keystoreStorePassword = providers.environmentVariable("PLP_STORE_PASSWORD").orNull
val keystoreKeyAlias = providers.environmentVariable("PLP_KEY_ALIAS").orNull
val keystoreKeyPassword = providers.environmentVariable("PLP_KEY_PASSWORD").orNull

// Source identity, not wall-clock time: unchanged inputs must leave BuildConfig up-to-date.
// Include paths and lengths so renames/deletions and assets/config changes affect the identity.
val sourceFingerprint = providers.provider {
    val sources = fileTree("src") {
        include("main/**", "debug/**", "release/**")
    }.files
    val configuration = listOf(
        file("build.gradle.kts"), file("proguard-rules.pro"),
        rootProject.file("build.gradle.kts"), rootProject.file("settings.gradle.kts"),
        rootProject.file("gradle.properties"), rootProject.file("gradle/wrapper/gradle-wrapper.properties"),
        rootProject.file("gradle/libs.versions.toml")
    ).filter { it.isFile }
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("pixivplus-source-v1".toByteArray(Charsets.UTF_8))
    (sources + configuration).distinct().sortedBy { it.relativeTo(rootProject.projectDir).invariantSeparatorsPath }
        .forEach { source ->
            val path = source.relativeTo(rootProject.projectDir).invariantSeparatorsPath.toByteArray(Charsets.UTF_8)
            val content = source.readBytes()
            digest.update(ByteBuffer.allocate(4).putInt(path.size).array())
            digest.update(path)
            digest.update(ByteBuffer.allocate(8).putLong(content.size.toLong()).array())
            digest.update(content)
        }
    "src-" + digest.digest().take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

android {
    namespace = "com.heihun.pixivplus"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.heihun.pixivplus"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "1.0.8"

        buildConfigField("String", "BUILD_FINGERPRINT", "\"${sourceFingerprint.get()}\"")
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

    sourceSets.getByName("test").resources.apply {
        srcDir("src/main")
        include("assets/pixivplus/i18n/*.json")
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.luckypray:dexkit:2.0.6")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
