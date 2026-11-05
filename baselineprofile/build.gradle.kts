plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

/**
 * Records the launcher's baseline profile (see BaselineProfileGenerator). A test module that drives the
 * nonMinifiedRelease build of :app from its own process; it is never part of the release APK.
 */
android {
    namespace = "org.arkikeskus.launcher.baselineprofile"
    compileSdk = 37

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    defaultConfig {
        minSdk = 30
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
    // The generator steers the launcher through UiAutomator from its own process instead of
    // instrumenting it, so the recorded profile is the launcher's own code only.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

baselineProfile {
    // Record on whatever emulator or phone is attached over adb; no Gradle-managed device.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

// AGP runs a connected test on EVERY attached device, the test phones included, and uninstalls the
// launcher from each afterwards. The recording therefore refuses to start unless ANDROID_SERIAL
// names the one emulator it is meant for.
tasks.configureEach {
    if (name.startsWith("connected")) {
        doFirst {
            check(!System.getenv("ANDROID_SERIAL").isNullOrBlank()) {
                "Set ANDROID_SERIAL to the emulator's serial (e.g. emulator-5554) before recording the baseline profile; " +
                    "without it the recording would run on, and uninstall from, every attached device."
            }
        }
    }
}

androidComponents {
    onVariants { variant ->
        val artifactsLoader = variant.artifacts.getBuiltArtifactsLoader()
        variant.instrumentationRunnerArguments.put(
            "targetAppId",
            variant.testedApks.map { artifactsLoader.load(it)?.applicationId },
        )
    }
}
