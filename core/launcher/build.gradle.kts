plugins {
    id("arkikeskus.android.library")
    id("arkikeskus.android.hilt")
}

android {
    namespace = "org.arkikeskus.launcher.launcher"
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Robolectric for the lock/unlock sound receiver.
    testImplementation(libs.junit)
    testImplementation(libs.google.truth)
    testImplementation(libs.robolectric)
}
