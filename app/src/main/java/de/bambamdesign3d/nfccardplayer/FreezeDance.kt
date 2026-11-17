package de.bambamdesign3d.nfccardplayer

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import kotlin.random.Random

/**
 * Freeze dance: music plays for a random time (playMin–playMax seconds),
 * then pauses for pauseMin–pauseMax seconds, and continues.
 * Works with the own player and – via media keys – with Amazon Music.
 */
object FreezeDance {
    var active = false
        private set
    var stopped = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var usedService = false
    private var playMin = 7_000L
    private var playMax = 30_000L
    private var pauseMin = 3_000L
    private var pauseMax = 8_000L

    fun toggle(ctx: Context, card: Card): Boolean {
        if (active) stop() else start(ctx, card)
        return active
    }

    fun start(ctx: Context, card: Card) {
        appContext = ctx.applicationContext
        playMin = card.playMin.coerceAtLeast(1) * 1000L
        playMax = maxOf(card.playMax, card.playMin).coerceAtLeast(1) * 1000L
        pauseMin = card.pauseMin.coerceAtLeast(1) * 1000L
        pauseMax = maxOf(card.pauseMax, card.pauseMin).coerceAtLeast(1) * 1000L
        active = true
        stopped = false
        handler.removeCallbacksAndMessages(null)
        scheduleStop()
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        active = false
        if (stopped) resume()
        stopped = false
    }

    private fun rnd(a: Long, b: Long) = if (b <= a) a else Random.nextLong(a, b + 1)

    private fun scheduleStop() {
        handler.postDelayed({
            if (!active) return@postDelayed
            if (isPlaying()) {
                pause()
                stopped = true
                handler.postDelayed({
                    if (!active) return@postDelayed
                    resume()
                    stopped = false
                    scheduleStop()
                }, rnd(pauseMin, pauseMax))
            } else {
                scheduleStop()
            }
        }, rnd(playMin, playMax))
    }

    private fun audio() = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private fun isPlaying(): Boolean =
        PlaybackService.instance?.isPlaying() == true || audio()?.isMusicActive == true

    private fun pause() {
        val svc = PlaybackService.instance
        if (svc != null && svc.isPlaying()) {
            usedService = true
            svc.pausePlayer()
        } else {
            usedService = false
            key(KeyEvent.KEYCODE_MEDIA_PAUSE)
        }
    }

    private fun resume() {
        if (usedService) PlaybackService.instance?.playPlayer() else key(KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    private fun key(code: Int) {
        val a = audio() ?: return
        a.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        a.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
}
