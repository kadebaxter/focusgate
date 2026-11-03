plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.kaden.focusgate"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.kaden.focusgate"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.play.services.location)
}
