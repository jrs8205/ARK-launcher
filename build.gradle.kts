import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener

// AGP 9 ships built-in Kotlin (default KGP). Bump it to our target Kotlin so we get the newest
// language version AND a matching Compose compiler. See https://kotl.in/gradle/agp-built-in-kotlin
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

// Declare plugins on the build classpath (apply false) so convention plugins in build-logic
// can apply them by id in each module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// Windows can't rewrite a jar that a process has open. KSP keeps its compiler loaded in the Gradle
// daemon from build to build (KspAATaskKt.isolatedClassLoaderCache), and the IntelliJ zip cache in
// it (ZipHandler) keeps up to 20 classpath jars open — the modules' own compile jars among them — so
// the next build that recompiled a module failed on its jar ("used by another process"). A
// single-module app never notices: the jars it leaves open are libraries nobody rewrites. Emptying
// that cache when every build ends closes them; KSP opens what it needs again on its next run.
abstract class CloseKspJarsAtBuildEnd : BuildService<BuildServiceParameters.None>, OperationCompletionListener, AutoCloseable {
    override fun onFinish(event: FinishEvent) = Unit

    override fun close() {
        // Reflection, and no failure ever: a KSP version without these names only brings the lock back.
        runCatching {
            val loaders = Class.forName("com.google.devtools.ksp.gradle.KspAATaskKt", false, CloseKspJarsAtBuildEnd::class.java.classLoader)
                .getMethod("getIsolatedClassLoaderCache").invoke(null) as Map<*, *>
            for (loader in loaders.values.filterIsInstance<ClassLoader>()) {
                runCatching {
                    loader.loadClass("ksp.com.intellij.openapi.vfs.impl.ZipHandler").getMethod("clearFileAccessorCache").invoke(null)
                }
            }
        }
    }
}

interface BuildEvents {
    @get:Inject val registry: BuildEventsListenerRegistry
}

objects.newInstance<BuildEvents>().registry.onTaskCompletion(
    gradle.sharedServices.registerIfAbsent("closeKspJarsAtBuildEnd", CloseKspJarsAtBuildEnd::class) {},
)
