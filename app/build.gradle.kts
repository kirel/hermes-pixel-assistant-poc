plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.danielkirs.hermesassistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.danielkirs.hermesassistant"
        minSdk = 29
        targetSdk = 35
        versionCode = 23
        versionName = "0.8.5"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}
