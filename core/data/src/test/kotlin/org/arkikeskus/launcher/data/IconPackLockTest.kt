package org.arkikeskus.launcher.data

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Parsing a big icon pack (tens of thousands of appfilter items) takes seconds. It used to run
 * inside the repository's monitor, so a package event on the main thread, which invalidates the
 * pack's cache, waited for the whole parse: the home screen froze. The parse now runs outside the
 * lock, and a result that overlapped an invalidation is not kept.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IconPackLockTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    @Test fun invalidateDoesNotWaitForAParseInProgress() {
        val parseStarted = CountDownLatch(1)
        val letTheParseFinish = CountDownLatch(1)
        var loads = 0
        val packs = IconPackRepository(context) { pkg ->
            loads++
            parseStarted.countDown()
            letTheParseFinish.await()
            IconPack(pkg, context.resources, mapOf("com.a/A" to "a"))
        }
        val parser = thread { packs.get("com.pack") }
        assertThat(parseStarted.await(5, TimeUnit.SECONDS)).isTrue()

        // The main thread's package event: must come back at once, not after the parse.
        val started = System.nanoTime()
        packs.invalidate("com.pack")
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        letTheParseFinish.countDown()
        parser.join(5_000)

        assertThat(waitedMs).isLessThan(1_000)
        // The parse that overlapped the invalidation is stale: the next use reads the pack again.
        packs.get("com.pack")
        assertThat(loads).isEqualTo(2)
    }

    @Test fun aParsedPackIsCachedForTheNextUse() {
        var loads = 0
        val packs = IconPackRepository(context) { pkg ->
            loads++
            IconPack(pkg, context.resources, emptyMap())
        }

        packs.get("com.pack")
        packs.get("com.pack")

        assertThat(loads).isEqualTo(1)
        assertThat(packs.invalidate("com.pack")).isTrue()
        assertThat(packs.invalidate("com.pack")).isFalse()
    }
}
