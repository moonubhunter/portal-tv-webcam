plugins {
    id("com.android.application")
}

android {
    namespace = "com.portalcam"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.portalcam"
        minSdk = 28
        targetSdk = 29
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
