plugins {
    `kotlin-dsl`
}

group = "org.arkikeskus.launcher.buildlogic"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "arkikeskus.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "arkikeskus.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "arkikeskus.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
    }
}
