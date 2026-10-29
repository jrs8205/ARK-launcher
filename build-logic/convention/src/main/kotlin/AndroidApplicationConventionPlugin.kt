import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure

/**
 * Configures an Android application module. AGP 9 provides built-in Kotlin compilation, so the
 * Kotlin plugin is NOT applied here; the Kotlin jvmTarget follows compileOptions.targetCompatibility.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")

        extensions.configure<ApplicationExtension> {
            compileSdk = 37
            defaultConfig {
                minSdk = 30
                targetSdk = 37
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }
        }
        // Unit-test JVM flags; configureEach runs after AGP has configured its test tasks.
        tasks.withType(Test::class.java).configureEach { jvmArgs(robolectricJvmArgs) }
    }
}
