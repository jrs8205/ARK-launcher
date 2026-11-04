plugins {
    id("arkikeskus.android.library")
}

android {
    namespace = "org.arkikeskus.launcher.model"
}

dependencies {
    // Plain JVM tests of the model's pure functions (DrawerSurface).
    testImplementation(libs.junit)
    testImplementation(libs.google.truth)
}
