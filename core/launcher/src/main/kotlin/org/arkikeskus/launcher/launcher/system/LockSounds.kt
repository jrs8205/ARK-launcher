package org.arkikeskus.launcher.launcher.system

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/** What locking and unlocking play: the owner's switch and the two picked sounds ("" = none). */
data class LockSoundChoice(val enabled: Boolean = false, val lockUri: String = "", val unlockUri: String = "")

/** Plays a picked sound ([ShortSoundPlayer] on the phone). */
fun interface SoundPlayer {
    fun play(uri: Uri)
}

/**
 * Not a moment for a sound: the phone is silent or on vibrate, Do not disturb holds anything back,
 * or a call is ringing or in progress — in a call the proximity sensor turns the screen off against
 * the ear, which would otherwise play the lock sound into it.
 */
internal fun quietNow(ringerMode: Int, interruptionFilter: Int, audioMode: Int): Boolean =
    ringerMode != AudioManager.RINGER_MODE_NORMAL ||
        (interruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            interruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) ||
        audioMode != AudioManager.MODE_NORMAL

/**
 * Plays the picked sound when the phone locks (the screen goes off) and when it unlocks (the user is
 * past the lock screen) — the phone's own lock sound can't be changed by an app, and on some phones
 * it is a poor one. Registered at run time in the launcher's process: these broadcasts reach no
 * manifest receiver. The phone's own sound, if on, still plays too; the setting says so.
 *
 * Exported: the unlock broadcast is sent by System UI's lock screen under its own app id, which a
 * not-exported receiver refuses. Only protected broadcasts are in the filter, and only the system
 * may send those.
 */
class LockSoundMonitor(
    private val context: Context,
    private val choice: () -> LockSoundChoice,
    private val player: SoundPlayer,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = choice()
            if (!now.enabled) return
            val uri = when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> now.lockUri
                Intent.ACTION_USER_PRESENT -> now.unlockUri
                else -> return
            }
            if (uri.isBlank()) return
            if (quietNow(audio.ringerMode, notifications.currentInterruptionFilter, audio.mode)) return
            player.play(uri.toUri())
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    fun stop() {
        if (!registered) return
        context.unregisterReceiver(receiver)
        registered = false
    }
}

/** The longest a picked sound plays; an alarm or a ringtone would run for many seconds. */
internal const val SOUND_MAX_MS = 3_000L
private const val SOUND_FADE_MS = 300L
private const val FADE_STEP_MS = 50L

/** The sound's volume [elapsedMs] after it started: full, then a short fade to silence at [SOUND_MAX_MS]. */
internal fun soundVolumeAt(elapsedMs: Long): Float {
    val fadeStart = SOUND_MAX_MS - SOUND_FADE_MS
    return when {
        elapsedMs <= fadeStart -> 1f
        elapsedMs >= SOUND_MAX_MS -> 0f
        else -> 1f - (elapsedMs - fadeStart).toFloat() / SOUND_FADE_MS
    }
}

/**
 * Plays a short sound at the volume of the system sounds (the stream the phone's own lock sound
 * uses), cut at [SOUND_MAX_MS] with a short fade; a new sound stops the one still playing. A sound
 * that can't be played (its file gone, its permission lost) is simply not heard.
 */
class ShortSoundPlayer(private val context: Context) : SoundPlayer {
    private val handler = Handler(Looper.getMainLooper())
    private var current: MediaPlayer? = null

    override fun play(uri: Uri) {
        stop()
        val player = MediaPlayer()
        current = player
        runCatching {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            player.setDataSource(context, uri)
            player.setOnPreparedListener { prepared ->
                if (current !== prepared) return@setOnPreparedListener
                prepared.start()
                fade(prepared, SystemClock.uptimeMillis())
            }
            player.setOnCompletionListener(::release)
            player.setOnErrorListener { failed, _, _ -> release(failed); true }
            player.prepareAsync()
        }.onFailure {
            Log.w("ShortSoundPlayer", "Cannot play $uri", it)
            release(player)
        }
    }

    fun stop() {
        current?.let(::release)
    }

    private fun fade(player: MediaPlayer, startedAt: Long) {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (current !== player) return
                val elapsed = SystemClock.uptimeMillis() - startedAt
                val volume = soundVolumeAt(elapsed)
                if (volume <= 0f) {
                    release(player)
                    return
                }
                runCatching { player.setVolume(volume, volume) }
                handler.postDelayed(this, FADE_STEP_MS)
            }
        }, SOUND_MAX_MS - SOUND_FADE_MS)
    }

    private fun release(player: MediaPlayer) {
        if (current === player) current = null
        runCatching { player.release() }
    }
}
