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
        try {
            assertThat(parseStarted.await(5, TimeUnit.SECONDS)).isTrue()

            // The main thread's package event: must come back at once, not after the parse. Run on a
            // thread of its own so a parse held under the monitor fails the test instead of hanging it.
            var wasCached = false
            val invalidator = thread { wasCached = packs.invalidate("com.pack") }
            invalidator.join(2_000)

            assertThat(invalidator.isAlive).isFalse()
            // A parse in progress counts as cached: the caller bumps the icon epochs on true, and
            // the icons rasterized from the stale parse must be fetched again.
            assertThat(wasCached).isTrue()
        } finally {
            letTheParseFinish.countDown()
        }
        parser.join(5_000)
        // The parse that overlapped the invalidation is stale: the next use reads the pack again.
        packs.get("com.pack")
        assertThat(loads).isEqualTo(2)
    }

    /** A pack updated while it is being parsed is read again before anything is returned: the
     *  caller must never get the old pack's icons, which Coil would cache under unchanged keys. */
    @Test fun aPackUpdatedDuringItsParseIsReadAgainBeforeItIsReturned() {
        val firstParseStarted = CountDownLatch(1)
        val letTheFirstParseFinish = CountDownLatch(1)
        var loads = 0
        val packs = IconPackRepository(context) { pkg ->
            val n = ++loads
            if (n == 1) {
                firstParseStarted.countDown()
                letTheFirstParseFinish.await()
            }
            IconPack(pkg, context.resources, mapOf("com.a/A" to "parse$n"))
        }
        var returned: IconPack? = null
        val getter = thread { returned = packs.get("com.pack") }
        try {
            assertThat(firstParseStarted.await(5, TimeUnit.SECONDS)).isTrue()
            val invalidator = thread { packs.invalidate("com.pack") }
            invalidator.join(2_000)
            assertThat(invalidator.isAlive).isFalse()
        } finally {
            letTheFirstParseFinish.countDown()
        }
        getter.join(5_000)

        assertThat(loads).isEqualTo(2)
        assertThat(returned!!.mappedDrawables()).containsExactly("parse2")
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
