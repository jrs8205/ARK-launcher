plugins {
    id("arkikeskus.android.library")
    id("arkikeskus.android.compose")
}

android {
    namespace = "org.arkikeskus.launcher.designsystem"
}

dependencies {
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
}
