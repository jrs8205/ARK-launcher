plugins {
    id("arkikeskus.android.feature")
}

android {
    namespace = "org.arkikeskus.launcher.feature.settings"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":feature:backup"))
}
