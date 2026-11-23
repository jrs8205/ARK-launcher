package org.arkikeskus.launcher.feature.appdrawer

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * A test's own thread is the main thread, and a view model's coroutines go on there only when its looper
 * turns. `runBlocking` on that thread stops the looper for as long as it waits, and what it waits for can be
 * a lock that was handed to one of those coroutines: the settings file makes its first read under a lock, and
 * every change of the settings and every switch of the home profile is made under one. The two then wait for
 * each other, with no limit ([WhileTheMainLooperTurnsTest]): a run of the whole test task stood still in
 * [DrawerFolderToHomeTest] until its worker was ended.
 *
 * So what a test asks of the stores it asks through [whileTheMainLooperTurns]. The files that may still say
 * `runBlocking` are listed with their reason; a new one has to be added here on purpose.
 */
class TestsDoNotBlockTheMainThreadTest {
    private val sources = File("src/test/kotlin")

    private val allowed = mapOf(
        "DrawerCellItemTest.kt" to "scrolls a Compose list on the main thread, where that is done, and asks no store",
        "WhileTheMainLooperTurnsTest.kt" to "shows the wait that does not end, cut off by a time limit",
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
