plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.shortcam.engine"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }
}
