plugins {
    id("arkikeskus.android.library")
    id("arkikeskus.android.compose")
}

android {
    namespace = "org.arkikeskus.launcher.ui"
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.coil.compose)
    implementation(libs.androidx.activity.compose)

    // JVM unit tests (pure icon/label sizing); Robolectric for the shortcut popup and LauncherApps calls.
    testImplementation(libs.junit)
    testImplementation(libs.google.truth)
    testImplementation(libs.robolectric)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
