plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.guard.autobrightness"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.guard.autobrightness"
        minSdk = 31
        targetSdk = 35
        versionCode = 140
        versionName = "1.4.0"
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}