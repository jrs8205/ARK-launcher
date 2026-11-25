plugins {
    id("arkikeskus.android.feature")
}

android {
    namespace = "org.arkikeskus.launcher.feature.appdrawer"
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    testImplementation(libs.junit)
    testImplementation(libs.google.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    // A settings store in memory for the tests that are not about the settings file.
    testImplementation(libs.androidx.datastore.preferences)
    testImplementation(testFixtures(project(":core:data")))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
