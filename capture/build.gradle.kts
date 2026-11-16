plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.shortcam.capture"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }
}

dependencies {
    api(project(":engine"))
    testImplementation(libs.junit)
}
