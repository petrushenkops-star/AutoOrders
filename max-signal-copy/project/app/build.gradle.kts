// All release builds must use the same persistent, private signing key.
// GitHub Actions unpacks it from AUTOORDERS_SIGNING_BUNDLE into MAXCOPY_* vars.
val storePath = System.getenv("MAXCOPY_SIGNING_KEYSTORE")
val storePasswordEnv = System.getenv("MAXCOPY_SIGNING_PASSWORD")
val keyAliasEnv = System.getenv("MAXCOPY_SIGNING_ALIAS")
val persistentSigning = !storePath.isNullOrBlank() &&
    !storePasswordEnv.isNullOrBlank() && !keyAliasEnv.isNullOrBlank()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.maxsignalcopy"
    compileSdk = 35
    defaultConfig {
        // DO NOT CHANGE: changing the ID prevents installation over the prior APK.
        applicationId = "com.example.maxsignalcopy"
        minSdk = 26
        targetSdk = 35
        // Increase versionCode for EVERY published update.
        versionCode = 22
        versionName = "0.4.9"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (persistentSigning) {
            create("persistent") {
                storeFile = file(storePath!!)
                storePassword = storePasswordEnv!!
                keyAlias = keyAliasEnv!!
                keyPassword = storePasswordEnv
                storeType = "pkcs12"
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (persistentSigning) signingConfig = signingConfigs.getByName("persistent")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("io.github.tdlib-android:core:0.1.1")
    testImplementation("junit:junit:4.13.2")
}

// Prevent accidental distribution of an unsigned "release" APK.
gradle.taskGraph.whenReady {
    if (allTasks.any { it.path.endsWith(":assembleRelease") ||
            it.path.endsWith(":bundleRelease") || it.path.endsWith(":packageRelease") } &&
        !persistentSigning
    ) error("Permanent signing configuration MAXCOPY_* is required for release builds")
}
