import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Applies Hilt + KSP and wires the Hilt runtime + compiler for an Android module. */
class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.google.devtools.ksp")
            apply("com.google.dagger.hilt.android")
        }

        // Hilt 2.59.2 bundles a kotlin-metadata-jvm that only reads metadata up to Kotlin 2.3.
        // Our Kotlin (2.4) emits 2.4 metadata, so force the matching kotlin-metadata-jvm version
        // onto the annotation-processor classpath so the Dagger/Hilt processor can read it.
        val kotlinVersion = libs.findVersion("kotlin").get().requiredVersion
        configurations.configureEach {
            resolutionStrategy.force("org.jetbrains.kotlin:kotlin-metadata-jvm:$kotlinVersion")
        }

        dependencies {
            add("implementation", libs.findLibrary("hilt-android").get())
            add("ksp", libs.findLibrary("hilt-compiler").get())
        }
    }
}
