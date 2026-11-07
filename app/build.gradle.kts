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
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 3) + 100
        versionName = "1.0-rc2"
    }
    signingConfigs {
        create("stableRelease") {
            val keystore = System.getenv("TVLIGHT_KEYSTORE_PATH")
            if (!keystore.isNullOrBlank()) {
                storeFile = file(keystore)
                storePassword = System.getenv("TVLIGHT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TVLIGHT_KEY_ALIAS")
                keyPassword = System.getenv("TVLIGHT_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (!System.getenv("TVLIGHT_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("stableRelease")
            }
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
