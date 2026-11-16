// Body package format, loader, and validation.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.shortcam.bodies"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }
}

dependencies {
    api(project(":engine"))
}
