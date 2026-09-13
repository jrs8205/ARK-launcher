import java.io.FileInputStream
import java.util.Properties

plugins {
    id("arkikeskus.android.application")
    id("arkikeskus.android.compose")
    id("arkikeskus.android.hilt")
}

// Release signing is read from keystore.properties (gitignored, never committed). On a machine
// without the key file the release build is simply left unsigned instead of failing.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}

android {
    namespace = "org.arkikeskus.launcher"

    defaultConfig {
        applicationId = "org.arkikeskus.launcher"
        versionCode = 29
        versionName = "0.7.14"
    }

    // F-Droid rejects the Play "Dependency metadata" signing block AGP adds by default.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // F-Droid's build server has no NDK, so AGP packages the native libraries untouched there.
    // Keep them untouched here too, whatever NDK this machine has, so the release build is
    // byte-identical on both sides.
    packaging {
        jniLibs {
            keepDebugSymbols += "**/*.so"
        }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            // The git-revision file AGP embeds would tie reproducibility to the exact checkout.
            vcsInfo {
                include = false
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":feature:home"))
    implementation(project(":feature:appdrawer"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:backup"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
}
