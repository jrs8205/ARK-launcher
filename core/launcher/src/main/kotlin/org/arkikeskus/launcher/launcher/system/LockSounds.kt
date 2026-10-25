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
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** What locking and unlocking play: the owner's switch and the two picked sounds ("" = none). */
data class LockSoundChoice(val enabled: Boolean = false, val lockUri: String = "", val unlockUri: String = "")

/**
 * Plays a picked sound ([ShortSoundPlayer] on the phone). Opening a file can take a while (a file
 * in the cloud), so [stillWanted] is asked again right before the sound starts: a call may have
 * begun, the setting gone off or another sound been picked meanwhile.
 */
fun interface SoundPlayer {
    fun play(uri: Uri, stillWanted: () -> Boolean)
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
            val action = intent.action ?: return
            val uri = soundFor(action) ?: return
            player.play(uri.toUri()) { soundFor(action) == uri }
        }
    }

    /** The sound [action] plays now, or null: the setting off, no sound picked, or a quiet moment. */
    private fun soundFor(action: String): String? {
        val now = choice()
        if (!now.enabled) return null
        val uri = when (action) {
            Intent.ACTION_SCREEN_OFF -> now.lockUri
            Intent.ACTION_USER_PRESENT -> now.unlockUri
            else -> return null
        }
        if (uri.isBlank()) return null
        if (quietNow(audio.ringerMode, notifications.currentInterruptionFilter, audio.mode)) return null
        return uri
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

/** One thread for every short sound in the process: files open there, one after another. */
private val soundWorker: Executor by lazy {
    Executors.newSingleThreadExecutor { Thread(it, "short-sounds").apply { isDaemon = true } }
}

/**
 * Plays a short sound at the volume of the system sounds (the stream the phone's own lock sound
 * uses), cut at [SOUND_MAX_MS] with a short fade; a new sound, or [stop], cancels the one still
 * playing or still opening. The file opens on [worker] — a provider may be slow (a cloud file) and
 * the caller is the main thread — and the sound starts on the main thread once prepared, if it is
 * still the latest and still wanted. A sound that can't be played (its file gone, its permission
 * lost) is simply not heard.
 */
class ShortSoundPlayer internal constructor(
    private val context: Context,
    private val worker: Executor,
    private val newPlayer: () -> MediaPlayer,
) : SoundPlayer {
    constructor(context: Context) : this(context, soundWorker, ::MediaPlayer)

    private val handler = Handler(Looper.getMainLooper())
    /** Bumped by every play and stop: a sound whose ticket is no longer current is dropped. */
    private val latest = AtomicInteger()
    /** The sound playing now; main thread only. */
    private var current: MediaPlayer? = null

    override fun play(uri: Uri, stillWanted: () -> Boolean) {
        stop()
        val ticket = latest.get()
        worker.execute {
            if (latest.get() != ticket) return@execute
            // The player has no looper on this thread, so its callbacks come on the main thread.
            val player = newPlayer()
            val prepared = runCatching {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                player.setDataSource(context, uri)
                player.setOnPreparedListener { ready ->
                    if (latest.get() != ticket || !stillWanted()) {
                        release(ready)
                        return@setOnPreparedListener
                    }
                    current = ready
                    ready.start()
                    fade(ready, SystemClock.uptimeMillis())
                }
                player.setOnCompletionListener(::release)
                player.setOnErrorListener { failed, _, _ -> release(failed); true }
                if (latest.get() != ticket) error("replaced while opening")
                player.prepareAsync()
            }
            prepared.onFailure {
                if (latest.get() == ticket) Log.w("ShortSoundPlayer", "Cannot play $uri", it)
                runCatching { player.release() }
            }
        }
    }

    /** Stops the sound playing now and drops one still opening. Main thread. */
    fun stop() {
        latest.incrementAndGet()
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
