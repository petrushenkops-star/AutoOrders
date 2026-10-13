// Persistent signing credentials are supplied from a GitHub Actions secret.
val signingKeystorePath = System.getenv("AUTOORDERS_SIGNING_KEYSTORE")
val signingPassword = System.getenv("AUTOORDERS_SIGNING_PASSWORD")
val signingAlias = System.getenv("AUTOORDERS_SIGNING_ALIAS")
val hasPersistentSigning = !signingKeystorePath.isNullOrBlank() &&
    !signingPassword.isNullOrBlank() && !signingAlias.isNullOrBlank()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.example.autoorders"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.autoorders"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "0.1.15"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (hasPersistentSigning) {
            create("persistentRelease") {
                storeFile = file(signingKeystorePath!!)
                storePassword = signingPassword!!
                keyAlias = signingAlias!!
                keyPassword = signingPassword
                storeType = "pkcs12"
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (hasPersistentSigning) {
                signingConfig = signingConfigs.getByName("persistentRelease")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
