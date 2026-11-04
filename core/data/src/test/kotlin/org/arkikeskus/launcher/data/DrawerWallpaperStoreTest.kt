package org.arkikeskus.launcher.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * The user's own picture behind the app drawer: saved as a file of its own with its mean lightness,
 * a new pick replaces the old file, a clear removes it, and a fresh store finds what the last one
 * left.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DrawerWallpaperStoreTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val dir = File(context.filesDir, "drawer_wallpaper")

    private fun picture(color: Int, width: Int = 40, height: Int = 20): Bitmap =
        createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test fun theMeanLuminanceIsTheWholePicturesLightness() {
        assertThat(meanLuminance(IntArray(4) { Color.WHITE })).isWithin(0.001f).of(1f)
        assertThat(meanLuminance(IntArray(4) { Color.BLACK })).isWithin(0.001f).of(0f)
        assertThat(meanLuminance(intArrayOf(Color.WHITE, Color.BLACK))).isWithin(0.001f).of(0.5f)
        assertThat(meanLuminance(IntArray(0))).isEqualTo(0f)
    }

    @Test fun theSampleSizeKeepsTheLongSideWithinTheLimit() {
        assertThat(sampleSizeFor(1000, 800, 1600)).isEqualTo(1)
        assertThat(sampleSizeFor(4000, 3000, 1600)).isEqualTo(3)
        assertThat(sampleSizeFor(3000, 4000, 1600)).isEqualTo(3)
        assertThat(sampleSizeFor(0, 0, 1600)).isEqualTo(1)
    }

    @Test fun aPictureIsSavedWithItsLightnessAndReplacesTheLastOne() = runTest {
        val store = DrawerWallpaperStore(context)
        assertThat(store.current.value).isNull()

        store.set(picture(Color.WHITE))
        val first = store.current.value!!
        assertThat(first.file.isFile).isTrue()
        assertThat(first.file.parentFile).isEqualTo(dir)
        assertThat(first.luminance).isWithin(0.01f).of(1f)

        store.set(picture(Color.BLACK))
        val second = store.current.value!!
        assertThat(second.file).isNotEqualTo(first.file) // a new name, so no cache shows the old one
        assertThat(first.file.exists()).isFalse()
        assertThat(second.luminance).isWithin(0.01f).of(0f)

        // A fresh store (the next launcher start) finds the picture and its lightness.
        val next = DrawerWallpaperStore(context)
        assertThat(next.current.value).isEqualTo(second)
    }

    @Test fun aClearRemovesThePictureForGood() = runTest {
        val store = DrawerWallpaperStore(context)
        store.set(picture(Color.GRAY))
        val file = store.current.value!!.file

        store.clear()

        assertThat(store.current.value).isNull()
        assertThat(file.exists()).isFalse()
        assertThat(DrawerWallpaperStore(context).current.value).isNull()
    }

    private fun jpgs() = dir.listFiles { f -> f.isFile && f.extension == "jpg" }.orEmpty().toList()

    /** A save that fails half-way (here: a recycled bitmap, which cannot be encoded) is reported, leaves no half-written
     *  picture behind, and keeps the picture that was there -- it must not throw into the caller's coroutine and take
     *  the launcher down. */
    @Test fun aFailedSaveKeepsThePreviousPictureAndSaysSo() = runTest {
        val store = DrawerWallpaperStore(context)
        assertThat(store.set(picture(Color.WHITE))).isTrue()
        val kept = store.current.value!!
        val broken = picture(Color.BLACK).apply { recycle() }

        assertThat(store.set(broken)).isFalse()

        assertThat(store.current.value).isEqualTo(kept)
        assertThat(kept.file.exists()).isTrue()
        assertThat(jpgs()).containsExactly(kept.file)
        assertThat(dir.listFiles { f -> f.extension == "part" }.orEmpty()).isEmpty()
    }

    /** The lightness is part of the picture's own file name, so the next start can never pair a picture with another
     *  picture's lightness: a shared file (as older builds wrote) counts only for a picture whose name has none. */
    @Test fun theLightnessTravelsWithThePictureItself() = runTest {
        val store = DrawerWallpaperStore(context)
        store.set(picture(Color.WHITE))
        val saved = store.current.value!!
        assertThat(saved.luminance).isWithin(0.01f).of(1f)
        File(dir, "luminance.txt").writeText("0.0")

        val next = DrawerWallpaperStore(context)

        assertThat(next.current.value).isEqualTo(saved)
        assertThat(next.current.value!!.luminance).isWithin(0.01f).of(1f)
    }

    /** A save the process did not live to publish leaves only its part-file behind: the next start ignores it, sweeps
     *  it, and keeps the picture that was published whole. */
    @Test fun aSaveInterruptedBeforeItsPublishIsSweptAtTheNextStart() = runTest {
        val store = DrawerWallpaperStore(context)
        store.set(picture(Color.BLACK))
        val published = store.current.value!!
        val leftover = File(dir, "half-written.part").apply { writeBytes(ByteArray(10)) }
        leftover.setLastModified(published.file.lastModified() + 60_000)

        val next = DrawerWallpaperStore(context)

        assertThat(next.current.value).isEqualTo(published)
        assertThat(leftover.exists()).isFalse()
    }

    /** A removal issued while a pick is still saving wins, even though the pick finishes later: the pick's result is
     *  dropped and its file removed, so the user's last action is what shows. */
    @Test fun aRemovalIssuedAfterAPickWinsEvenWhenThePickFinishesLater() = runBlocking<Unit> {
        val io = StandardTestDispatcher()
        val store = DrawerWallpaperStore(context, io)
        val pick = launch { store.set(picture(Color.WHITE)) }
        val removal = launch { store.clear() }
        yield() // both have started: the pick came first, the removal after it; neither has saved yet
        io.scheduler.advanceUntilIdle()
        pick.join()
        removal.join()

        assertThat(store.current.value).isNull()
        assertThat(jpgs()).isEmpty()
    }

    /** A removal that the pool happens to run AFTER a pick issued later must stand down: the pick is the user's last
     *  action. Dispatched blocks run last-in-first-out here, so the pick's save runs before the removal's block. */
    @Test fun aRemovalOvertakenByALaterPickStandsDown() = runBlocking<Unit> {
        val io = LastInFirstOutDispatcher()
        val store = DrawerWallpaperStore(context, io)
        val removal = launch { store.clear() }
        val pick = launch { store.set(picture(Color.BLACK)) }
        yield() // the removal took its ticket first, the pick after it
        io.runAll()
        removal.join()
        pick.join()

        assertThat(store.current.value!!.luminance).isWithin(0.01f).of(0f)
        assertThat(jpgs()).containsExactly(store.current.value!!.file)
        assertThat(DrawerWallpaperStore(context).current.value!!.luminance).isWithin(0.01f).of(0f) // its lightness too
    }

    /** A stale pick (one a later pick overtook) must not touch the shared lightness file either: the next start would
     *  pair the accepted dark picture with the stale light one's lightness and draw black text on black. */
    @Test fun aStalePickLeavesTheAcceptedPicturesLightnessAlone() = runBlocking<Unit> {
        val io = LastInFirstOutDispatcher()
        val store = DrawerWallpaperStore(context, io)
        val stale = launch { store.set(picture(Color.WHITE)) }
        val accepted = launch { store.set(picture(Color.BLACK)) }
        yield()
        io.runAll() // the later (black) pick saves first, the stale (white) one after it
        stale.join()
        accepted.join()

        assertThat(store.current.value!!.luminance).isWithin(0.01f).of(0f)
        assertThat(DrawerWallpaperStore(context).current.value!!.luminance).isWithin(0.01f).of(0f)
    }

    /** A crash between a pick's rename and the deletion of the picture it replaced leaves two published pictures: the
     *  next start shows the newest and sweeps the other, so nothing older can come back later. */
    @Test fun aReplacedPictureLeftBehindByACrashIsSweptAtTheNextStart() = runTest {
        val store = DrawerWallpaperStore(context)
        store.set(picture(Color.BLACK))
        val published = store.current.value!!
        val leftover = File(dir, "leftover-0.900.jpg").apply { writeBytes(ByteArray(10)) }
        leftover.setLastModified(published.file.lastModified() - 60_000)

        val next = DrawerWallpaperStore(context)

        assertThat(next.current.value).isEqualTo(published)
        assertThat(leftover.exists()).isFalse()
        assertThat(jpgs()).containsExactly(published.file)
    }

    /** The owner's review: with two published pictures on disk (a crash's leftover beside the current one) a removal
     *  must leave nothing for the next start to bring back. */
    @Test fun aRemovalWithTwoPublishedPicturesOnDiskLeavesNothingToComeBack() = runTest {
        val store = DrawerWallpaperStore(context)
        store.set(picture(Color.BLACK))
        val leftover = File(dir, "leftover-0.900.jpg").apply { writeBytes(ByteArray(10)) }
        leftover.setLastModified(store.current.value!!.file.lastModified() - 60_000)

        store.clear()

        assertThat(store.current.value).isNull()
        assertThat(jpgs()).isEmpty()
        assertThat(DrawerWallpaperStore(context).current.value).isNull()
    }

    /** Runs what is dispatched to it in reverse order, on the caller's thread, when asked. */
    private class LastInFirstOutDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addFirst(block) }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    /** Of two picks in a row the later one shows, whatever order their saves finish in. */
    @Test fun theLaterOfTwoPicksWins() = runBlocking<Unit> {
        val io = StandardTestDispatcher()
        val store = DrawerWallpaperStore(context, io)
        val first = launch { store.set(picture(Color.WHITE)) }
        val second = launch { store.set(picture(Color.BLACK)) }
        yield()
        io.scheduler.advanceUntilIdle()
        first.join()
        second.join()

        assertThat(store.current.value!!.luminance).isWithin(0.01f).of(0f)
        assertThat(jpgs()).containsExactly(store.current.value!!.file)
    }
}
