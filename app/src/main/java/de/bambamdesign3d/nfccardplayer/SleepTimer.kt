package de.bambamdesign3d.nfccardplayer

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Schlaf-Timer: blendet die letzten 30 Sekunden aus und pausiert dann.
 * Läuft im App-Prozess, der durch den Abspiel-Dienst am Leben bleibt.
 */
object SleepTimer {
    private var FADE_MS = 30_000L

    private val handler = Handler(Looper.getMainLooper())
    private var endAt = 0L
    private var minuten = 0
    private var appContext: Context? = null

    val active: Boolean get() = endAt > 0L

    fun remainingMs(): Long = if (active) (endAt - SystemClock.elapsedRealtime()).coerceAtLeast(0) else 0

    fun label(): String {
        val m = (remainingMs() + 59_999) / 60_000
        return t("⏱ noch $m min", "⏱ $m min left")
    }

    /** Card that started the timer (same card again = timer off). */
    var cardNumber: Int? = null
        private set

    fun start(ctx: Context, min: Int, fadeSec: Int = 30, card: Int? = null) {
        cancel()
        cardNumber = card
        FADE_MS = fadeSec.coerceIn(1, 600) * 1000L
        appContext = ctx.applicationContext
        minuten = min
        endAt = SystemClock.elapsedRealtime() + min * 60_000L
        handler.postDelayed(tick, 1000)
    }

    fun cancel() {
        handler.removeCallbacks(tick)
        endAt = 0L
        PlaybackService.instance?.setVolumeFactor(1f)
    }

    private val tick = object : Runnable {
        override fun run() {
            val rest = remainingMs()
            if (rest <= 0L) {
                fire()
                return
            }
            if (rest < FADE_MS) {
                PlaybackService.instance?.setVolumeFactor(rest.toFloat() / FADE_MS)
            }
            handler.postDelayed(this, if (rest < FADE_MS) 500 else 1000)
        }
    }

    private fun fire() {
        endAt = 0L
        val svc = PlaybackService.instance
        if (svc != null && svc.isPlaying()) {
            svc.pauseAndRestoreVolume()
        } else {
            svc?.setVolumeFactor(1f)
            // z. B. Amazon Music: Pause an die aktive Wiedergabe senden
            val audio = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        }
    }
}
