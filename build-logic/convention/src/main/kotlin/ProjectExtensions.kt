import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/**
 * Access the `libs` version catalog from inside a convention plugin.
 * MUST be `internal` so it does not leak into consuming module build scripts and shadow the
 * generated type-safe `libs` accessor (which would break `libs.androidx.*` references).
 */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * JVM flags for the unit-test JVM. Robolectric 4.17+ reaches OpenJDK internals (java.io,
 * jdk.internal.access, ...) that JDK 17+ encapsulates, so the test JVM must open them; the list is
 * Robolectric's own (robolectric.org/getting-started, "Running with Java 17 and higher").
 */
internal val robolectricJvmArgs: List<String> = listOf(
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/java.security=ALL-UNNAMED",
    "--add-opens=java.base/java.text=ALL-UNNAMED",
    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
    "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
    "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
)
