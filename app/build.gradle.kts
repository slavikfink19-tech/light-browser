plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ua.tvlight.browser"
    compileSdk = 35
    defaultConfig {
        applicationId = "ua.tvlight.browser"
        minSdk = 23
        targetSdk = 28
        versionCode = 2
        versionName = "1.0-rc1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
