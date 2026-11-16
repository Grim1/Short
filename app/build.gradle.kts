plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.shortcam"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.shortcam"
        minSdk = 31
        targetSdk = 37
        versionCode = 2
        versionName = "0.1"
    }

    buildTypes {
        release {
            // Strips unused code and resources and optimises what is left.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":bodies"))
    implementation(project(":board"))
    implementation(project(":capture"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
}
