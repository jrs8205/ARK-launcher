package org.arkikeskus.launcher.launcher.system

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The phone's own lock sound can't be changed by an app, and on some phones it is a poor one: with
 * the owner's setting on, ARK plays a sound the user picked when the phone locks and when it unlocks
 * — but never when the phone is silent or on vibrate, in Do not disturb, or in a call (the screen
 * goes off against the ear then).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LockSoundsTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val played = mutableListOf<Uri>()
    private var stillWanted: () -> Boolean = { error("nothing played") }
    private var choice = LockSoundChoice(enabled = true, lockUri = "content://sounds/lock", unlockUri = "content://sounds/unlock")
    private val monitor = LockSoundMonitor(context, { choice }) { uri, wanted ->
        played += uri
        stillWanted = wanted
    }

    @After fun tearDown() = monitor.stop()

    private fun send(action: String) {
        context.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun lockingAndUnlockingPlayTheirSounds() {
        monitor.start()

        send(Intent.ACTION_SCREEN_OFF)
        send(Intent.ACTION_USER_PRESENT)

        assertThat(played).containsExactly(Uri.parse("content://sounds/lock"), Uri.parse("content://sounds/unlock")).inOrder()
    }

    /**
     * The unlock broadcast comes from System UI's lock screen, which runs under its own app id, not
     * the system server's: a not-exported receiver never gets it (found on the emulator — the lock
     * sound played, the unlock sound never did). Exported is safe here: both actions are protected
     * broadcasts that only the system may send.
     */
    @Test fun theReceiverTakesTheLockScreensUnlockBroadcast() {
        monitor.start()

        val registered = shadowOf(context).registeredReceivers.single { it.intentFilter.hasAction(Intent.ACTION_USER_PRESENT) }

        assertThat(registered.flags and Context.RECEIVER_EXPORTED).isNotEqualTo(0)
        assertThat(registered.intentFilter.actionsIterator().asSequence().toList())
            .containsExactly(Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT)
    }

    @Test fun nothingPlaysWithTheSettingOff() {
        choice = choice.copy(enabled = false)
        monitor.start()

        send(Intent.ACTION_SCREEN_OFF)
        send(Intent.ACTION_USER_PRESENT)

        assertThat(played).isEmpty()
    }

    @Test fun anEventWithoutAPickedSoundStaysSilent() {
        choice = choice.copy(lockUri = "")
        monitor.start()

        send(Intent.ACTION_SCREEN_OFF)
        send(Intent.ACTION_USER_PRESENT)

        assertThat(played).containsExactly(Uri.parse("content://sounds/unlock"))
    }

    @Test fun aSilentPhoneStaysSilent() {
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        monitor.start()

        send(Intent.ACTION_SCREEN_OFF)

        assertThat(played).isEmpty()
    }

    /** In a call the proximity sensor turns the screen off against the ear: no lock sound in the ear. */
    @Test fun aCallStaysSilent() {
        audio.mode = AudioManager.MODE_IN_CALL
        monitor.start()

        send(Intent.ACTION_SCREEN_OFF)

        assertThat(played).isEmpty()
    }

    @Test fun afterStopNothingPlays() {
        monitor.start()
        monitor.stop()

        send(Intent.ACTION_SCREEN_OFF)

        assertThat(played).isEmpty()
    }

    /** Opening the file takes a while: when it is ready the player asks again, and the answer
     *  follows the setting, the picked sound and the phone's state at that moment. */
    @Test fun theSoundIsStillWantedOnlyWhileNothingChanged() {
        monitor.start()
        send(Intent.ACTION_SCREEN_OFF)
        assertThat(stillWanted()).isTrue()

        audio.mode = AudioManager.MODE_IN_CALL
        assertThat(stillWanted()).isFalse()
        audio.mode = AudioManager.MODE_NORMAL
        assertThat(stillWanted()).isTrue()

        choice = choice.copy(lockUri = "content://sounds/other")
        assertThat(stillWanted()).isFalse()
        choice = choice.copy(lockUri = "content://sounds/lock", enabled = false)
        assertThat(stillWanted()).isFalse()
    }

    @Test fun quietMeansSilentVibrateDoNotDisturbOrACall() {
        val normal = AudioManager.RINGER_MODE_NORMAL
        val all = NotificationManager.INTERRUPTION_FILTER_ALL
        val noCall = AudioManager.MODE_NORMAL
        assertThat(quietNow(normal, all, noCall)).isFalse()
        assertThat(quietNow(AudioManager.RINGER_MODE_VIBRATE, all, noCall)).isTrue()
        assertThat(quietNow(AudioManager.RINGER_MODE_SILENT, all, noCall)).isTrue()
        assertThat(quietNow(normal, NotificationManager.INTERRUPTION_FILTER_PRIORITY, noCall)).isTrue()
        assertThat(quietNow(normal, NotificationManager.INTERRUPTION_FILTER_NONE, noCall)).isTrue()
        assertThat(quietNow(normal, all, AudioManager.MODE_IN_COMMUNICATION)).isTrue()
        assertThat(quietNow(normal, all, AudioManager.MODE_RINGTONE)).isTrue()
    }

    /** An alarm or a ringtone runs for many seconds: the sound is cut at 3 s with a short fade. */
    @Test fun aLongSoundFadesOutAndStopsAtThreeSeconds() {
        assertThat(soundVolumeAt(0L)).isEqualTo(1f)
        assertThat(soundVolumeAt(2_600L)).isEqualTo(1f)
        assertThat(soundVolumeAt(2_850L)).isWithin(0.01f).of(0.5f)
        assertThat(soundVolumeAt(3_000L)).isEqualTo(0f)
        assertThat(SOUND_MAX_MS).isEqualTo(3_000L)
    }
}
