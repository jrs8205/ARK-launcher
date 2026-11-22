package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * The tests' own helper for work that needs the home profile's hold ([whileTheMainLooperTurns]).
 *
 * Review round 19. Out of time, the helper stopped turning the main looper and then waited for its work to
 * end, as a parent waits for a child. Work that cannot be cancelled (a profile's deletion waits for the hold
 * in a step that is not cancellable) ends only when whoever has the hold has had the turn of the looper it
 * needs: the two waited for each other, and a test that should have failed hung instead. The looper turns on
 * while the work ends, and the helper is back with its failure whether the work ends or not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WhileTheMainLooperTurnsTest {

    @Test fun workThatOutlastsTheLimitFailsTheTestAndIsGivenTheLooperItNeedsToEnd() = runTest(timeout = 8.seconds) {
        val hold = Mutex(locked = true)
        val ended = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())

        val failure = try {
            whileTheMainLooperTurns("the hold", limitMs = 300) {
                try {
                    awaitCancellation()
                } finally {
                    // The helper's time is up (it has told the work to end). Whoever has the hold lets go of
                    // it on the main looper, and the work waits for the hold in a step nothing can cancel:
                    // it ends only if the looper still turns.
                    main.post { hold.unlock() }
                    withContext(NonCancellable) { hold.withLock { ended.set(true) } }
                }
            }
            null
        } catch (timedOut: IllegalStateException) {
            timedOut
        }

        assertThat(failure).hasMessageThat().contains("Timed out waiting for the hold")
        // The work was let run to its end: nothing of it is left waiting for a hold after the test.
        assertThat(ended.get()).isTrue()
    }

    /** Work that never ends, looper or no looper, still gives the test its failure and not a hang. */
    @Test fun workThatNeverEndsStillFailsTheTest() = runTest(timeout = 8.seconds) {
        val never = Mutex(locked = true)

        val failure = try {
            whileTheMainLooperTurns("what never comes", limitMs = 200) { withContext(NonCancellable) { never.withLock { } } }
            null
        } catch (timedOut: IllegalStateException) {
            timedOut
        }

        assertThat(failure).hasMessageThat().contains("Timed out waiting for what never comes")
    }

    @Test fun workThatEndsInTimeHandsBackWhatItGave() = runTest {
        assertThat(whileTheMainLooperTurns("an answer") { 42 }).isEqualTo(42)
    }
}
