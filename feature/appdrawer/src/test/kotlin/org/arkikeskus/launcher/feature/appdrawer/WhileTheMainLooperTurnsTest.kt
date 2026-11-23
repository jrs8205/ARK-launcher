package org.arkikeskus.launcher.feature.appdrawer

import android.app.Application
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * How a test asks the stores for something while a view model is alive ([whileTheMainLooperTurns]).
 *
 * A run of the whole test task stood still in [DrawerFolderToHomeTest], with its own thread inside
 * `runBlocking { homeItems.first() }` and no other thread at work, until the worker was ended. The settings
 * file makes its first read under a lock, and whoever asks the file meanwhile waits in line for that lock:
 * the view model's coroutines, which had asked first, and then the test. The read over, the lock went to the
 * first in line, a coroutine of the main thread, which goes on when the main looper turns; the test's thread
 * IS the main thread, and it was waiting. On a quiet machine the first read is over before anyone is in
 * line, which is why it happened once in many runs, and in a run of everything at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class WhileTheMainLooperTurnsTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val wiring = DrawerWiring(context, TestScope(), settingsFirstReadWaits = true)

    /** Coroutines of the main thread, as a view model's are. */
    private val asAViewModel = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val elsewhere = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() {
        wiring.settingsFirstReadMayEnd.countDown()
        asAViewModel.cancel()
        elsewhere.cancel()
        wiring.close()
    }

    /** What the test that stood still asked for: the rows of the home profile that is on, which the settings
     *  file names. */
    private suspend fun homeRows(): List<HomeItemEntity> = wiring.homeLayoutRepository.homeItems.first()

    /** The settings file's first read is under way on another thread, and a coroutine of the main thread
     *  has asked the file too: it is in line for the lock the read is made under, ahead of the test. */
    private fun aViewModelIsInLineForTheFirstRead() {
        elsewhere.launch { homeRows() }
        check(wiring.settingsFirstReadBegan.await(10, TimeUnit.SECONDS)) { "the settings file's first read never began" }
        asAViewModel.launch { homeRows() }
    }

    /** The wait the rule is for, cut off by a time limit here: with no limit it does not end. */
    @Test fun askedOnTheTestsOwnThreadTheRowsNeverCome() {
        aViewModelIsInLineForTheFirstRead()

        val rows = runBlocking {
            // The first read ends once this thread is in line too. Its lock then goes to the coroutine of
            // the main thread, which waits for the looper that this thread, waiting here, does not turn.
            launch {
                delay(300)
                wiring.settingsFirstReadMayEnd.countDown()
            }
            withTimeoutOrNull(2_000) { homeRows() }
        }

        assertThat(rows).isNull()
    }

    @Test fun askedWhileTheMainLooperTurnsTheRowsCome() {
        aViewModelIsInLineForTheFirstRead()
        elsewhere.launch {
            delay(300)
            wiring.settingsFirstReadMayEnd.countDown()
        }

        val rows = whileTheMainLooperTurns("the home rows") { homeRows() }

        assertThat(rows).isEmpty()
    }

    @Test fun workThatEndsInTimeHandsBackWhatItGave() {
        assertThat(whileTheMainLooperTurns("an answer") { 42 }).isEqualTo(42)
    }

    @Test fun whatTheWorkThrowsIsThrownToTheTest() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            whileTheMainLooperTurns<Unit>("a refusal") { throw IllegalArgumentException("refused") }
        }

        assertThat(thrown).hasMessageThat().isEqualTo("refused")
    }

    /** Work that never ends, looper or no looper, gives the test its failure and not a hang. */
    @Test fun workThatNeverEndsFailsTheTest() {
        val never = Mutex(locked = true)

        val failure = assertThrows(IllegalStateException::class.java) {
            whileTheMainLooperTurns("what never comes", limitMs = 200) { withContext(NonCancellable) { never.withLock { } } }
        }

        assertThat(failure).hasMessageThat().contains("Timed out waiting for what never comes")
    }
}
