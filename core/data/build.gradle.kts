plugins {
    id("arkikeskus.android.library")
    id("arkikeskus.android.hilt")
}

android {
    namespace = "org.arkikeskus.launcher.data"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

ksp {
    // Export Room schemas here so every version bump leaves a JSON the next Migration can diff against.
    arg("room.schemaLocation", "$projectDir/schemas")
}

// NOTE: when the first real Migration + MigrationTestHelper test is added, register the exported
// schemas as androidTest assets so the helper can load them. The AGP 9 Kotlin-DSL `sourceSets[...]`
// accessor throws a ClassCastException, so do it then with the API that works on this AGP, e.g.
// androidComponents/onVariants or copying schemas into androidTest assets via a Copy task.

dependencies {
    implementation(project(":core:model"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // JVM unit tests (SettingsRepository with a temp-file DataStore).
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.google.truth)
    testImplementation(libs.json)

    // Instrumented tests (HomeLayoutRepository against a real in-memory Room database).
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.google.truth)
}
