package org.arkikeskus.launcher.feature.home

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * A test's own thread is the main thread, and a view model's coroutines go on there only when its looper
 * turns. `runBlocking` on that thread stops the looper for as long as it waits, and what it waits for can be
 * a lock that was handed to one of those coroutines: the settings file makes its first read under a lock, and
 * every change of the settings and every switch of the home profile is made under one. The two then wait for
 * each other, with no limit: a run of the whole test task stood still so in the drawer's tests until its
 * worker was ended (what happens is shown there, in the drawer's own test of this helper).
 *
 * So what a test asks of the stores it asks through [whileTheMainLooperTurns]. The files that may still say
 * `runBlocking` are listed with their reason; a new one has to be added here on purpose.
 */
class TestsDoNotBlockTheMainThreadTest {
    private val sources = File("src/test/kotlin")

    private val allowed = mapOf(
        "PinRequestAcrossProfilesTest.kt" to "a switch made inside the view model's own step, on the thread that step runs on",
    )

    private val blocking = Regex("""\brunBlocking\b""")

    /** This file names what it looks for. */
    private val self = "TestsDoNotBlockTheMainThreadTest.kt"

    private fun kotlinFiles(): List<File> {
        assertWithMessage("$sources is missing: the test runs from the module's directory").that(sources.isDirectory).isTrue()
        return sources.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != self }.toList()
    }

    /** The line without what a comment says. */
    private fun codeOf(line: String): String {
        val trimmed = line.trimStart()
        return if (trimmed.startsWith("*") || trimmed.startsWith("/*")) "" else line.substringBefore("//")
    }

    private fun blocks(file: File): Boolean = file.readLines().any { blocking.containsMatchIn(codeOf(it)) }

    @Test fun noTestWaitsOnItsOwnThreadForTheStores() {
        val offenders = kotlinFiles().filter { it.name !in allowed }.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> blocking.containsMatchIn(codeOf(line)) }
                .map { (i, line) -> "${file.name}:${i + 1}: ${line.trim()}" }
        }
        assertWithMessage("runBlocking on the test's own thread stops the main looper; ask through whileTheMainLooperTurns")
            .that(offenders).isEmpty()
    }

    @Test fun theAllowListNamesOnlyFilesThatStillNeedIt() {
        val files = kotlinFiles()
        for (name in allowed.keys) {
            val file = files.singleOrNull { it.name == name }
            assertWithMessage("$name is on the allow list but is not among the module's tests").that(file).isNotNull()
            assertWithMessage("$name no longer says runBlocking: take it off the allow list").that(blocks(file!!)).isTrue()
        }
    }
}
