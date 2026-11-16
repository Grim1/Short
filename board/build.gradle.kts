// Compose board view, wires, controls, and touch handling.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.shortcam.board"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    api(project(":bodies"))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
}
