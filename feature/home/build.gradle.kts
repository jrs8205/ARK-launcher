plugins {
    id("arkikeskus.android.feature")
}

android {
    namespace = "org.arkikeskus.launcher.feature.home"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:designsystem"))
}
