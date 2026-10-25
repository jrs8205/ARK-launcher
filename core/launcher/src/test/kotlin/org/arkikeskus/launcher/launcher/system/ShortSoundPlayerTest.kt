package org.arkikeskus.launcher.launcher.system

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Looper
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The picked sound may be a file in the cloud: opening it can take a while. It opens off the main
 * thread, so the launcher never freezes, and the moment is checked again when the sound is ready —
 * a call may have begun or the setting gone off meanwhile, or another sound may have replaced it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShortSoundPlayerTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val uri = Uri.parse("content://cloud/sound/1")

    private class FakePlayer : MediaPlayer() {
        var openedOn: Thread? = null
        var started = false
        var released = false
        var onPrepared: OnPreparedListener? = null

        override fun setAudioAttributes(attributes: AudioAttributes) = Unit
        override fun setDataSource(context: Context, uri: Uri) {
            openedOn = Thread.currentThread()
        }
        override fun setOnPreparedListener(listener: OnPreparedListener?) {
            onPrepared = listener
        }
        override fun prepareAsync() = Unit
        override fun start() {
            started = true
        }
        override fun release() {
            released = true
        }
        fun ready() = onPrepared!!.onPrepared(this)
    }

    private val players = mutableListOf<FakePlayer>()
    private fun player(worker: Executor = Executor(Runnable::run)) =
        ShortSoundPlayer(context, worker) { FakePlayer().also { players += it } }

    @Test fun theFileOpensOffTheMainThread() {
        val worker = Executors.newSingleThreadExecutor()
        player(worker).play(uri) { true }
        worker.shutdown()
        assertThat(worker.awaitTermination(2, TimeUnit.SECONDS)).isTrue()

        assertThat(players.single().openedOn).isNotNull()
        assertThat(players.single().openedOn).isNotSameInstanceAs(Looper.getMainLooper().thread)
    }

    @Test fun aWantedSoundStartsWhenReady() {
        player().play(uri) { true }

        players.single().ready()

        assertThat(players.single().started).isTrue()
    }

    /** A call began, or the setting went off, while the file was opening: it is not heard. */
    @Test fun aSoundNoLongerWantedWhenReadyIsNotStarted() {
        var wanted = true
        player().play(uri) { wanted }

        wanted = false
        players.single().ready()

        assertThat(players.single().started).isFalse()
        assertThat(players.single().released).isTrue()
    }

    @Test fun aStoppedSoundDoesNotStartWhenReady() {
        val sounds = player()
        sounds.play(uri) { true }

        sounds.stop()
        players.single().ready()

        assertThat(players.single().started).isFalse()
        assertThat(players.single().released).isTrue()
    }

    /**
     * The native player holds only a weak reference to its Java object: between the opening and the
     * prepared callback the player must be kept, or a garbage collection frees it and the sound is
     * never heard. Here the test keeps nothing but a weak reference.
     */
    @Test fun aPreparingSoundIsKeptAliveUntilItIsReady() {
        var weak: WeakReference<FakePlayer>? = null
        val sounds = ShortSoundPlayer(context, Executor(Runnable::run)) { FakePlayer().also { weak = WeakReference(it) } }
        sounds.play(uri) { true }

        repeat(5) {
            System.gc()
            System.runFinalization()
            Thread.sleep(20)
        }

        val player = weak!!.get()
        assertThat(player).isNotNull()
        player!!.ready()
        assertThat(player.started).isTrue()
    }

    /** A sound still preparing when it is stopped or replaced is let go at once, not when ready. */
    @Test fun stoppingReleasesASoundStillPreparing() {
        val sounds = player()
        sounds.play(uri) { true }

        sounds.stop()

        assertThat(players.single().released).isTrue()
        players.single().ready()
        assertThat(players.single().started).isFalse()
    }

    @Test fun aNewerSoundReplacesOneStillOpening() {
        val sounds = player()
        sounds.play(uri) { true }
        sounds.play(Uri.parse("content://cloud/sound/2")) { true }

        players[0].ready()
        players[1].ready()

        assertThat(players[0].started).isFalse()
        assertThat(players[1].started).isTrue()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
