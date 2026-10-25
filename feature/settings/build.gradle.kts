plugins {
    id("arkikeskus.android.feature")
}

android {
    namespace = "org.arkikeskus.launcher.feature.settings"
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:launcher"))
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":feature:backup"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.google.truth)
    testImplementation(libs.robolectric)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
