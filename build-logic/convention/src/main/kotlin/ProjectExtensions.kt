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
