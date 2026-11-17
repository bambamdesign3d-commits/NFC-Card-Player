package de.bambamdesign3d.nfccardplayer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent

/**
 * Starts playlists in Amazon Music or Spotify – without special permissions:
 * 1. opens the link in the app with autoplay hints
 * 2. if nothing plays after 5 s, presses "play" once (media key)
 * 3. as soon as music plays, the app comes back to the front (overlay permission).
 */
object StreamStarter {
    const val PKG_AMAZON = "com.amazon.mp3"
    const val PKG_SPOTIFY = "com.spotify.music"
    private const val TIMEOUT_MS = 25_000L

    private val handler = Handler(Looper.getMainLooper())
    private var runId = 0

    fun pkg(card: Card) = if (card.isSpotify) PKG_SPOTIFY else PKG_AMAZON

    fun canReturn(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun cancel() {
        runId++
    }

    /**
     * Tiny invisible overlay window while a streaming app starts. With a visible overlay window
     * Android allows the app to bring itself back to the front (background activity start).
     */
    private var anchor: android.view.View? = null

    private fun showAnchor(ctx: Context) {
        if (anchor != null || !Settings.canDrawOverlays(ctx)) return
        try {
            val wm = ctx.getSystemService(android.view.WindowManager::class.java)
            val v = android.view.View(ctx).apply { setBackgroundColor(android.graphics.Color.argb(1, 0, 0, 0)) }
            val lp = android.view.WindowManager.LayoutParams(
                1, 1,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START }
            wm.addView(v, lp)
            anchor = v
            // safety: never keep it longer than 40 s
            handler.postDelayed({ hideAnchor(ctx) }, 40_000)
        } catch (_: Exception) {
        }
    }

    private fun hideAnchor(ctx: Context) {
        val v = anchor ?: return
        anchor = null
        try { ctx.getSystemService(android.view.WindowManager::class.java).removeView(v) } catch (_: Exception) {}
    }

    /** spotify:playlist:ID from https://open.spotify.com/playlist/ID?si=… */
    fun spotifyUri(link: String): String? {
        if (link.startsWith("spotify:")) return link
        val m = Regex("""open\.spotify\.com/(?:intl-[a-z]+/)?(playlist|album|track|artist|show|episode)/([A-Za-z0-9]+)""").find(link)
            ?: return null
        return "spotify:${m.groupValues[1]}:${m.groupValues[2]}"
    }

    private fun amazonAutoplay(link: String): String =
        if (link.contains("music.amazon") && !link.contains("do=play"))
            link + (if (link.contains("?")) "&" else "?") + "do=play"
        else link

    fun launch(activity: Activity, card: Card): Boolean {
        val app = activity.applicationContext
        val link = card.link ?: return false
        val pkg = pkg(card)
        val spUri = if (card.isSpotify) spotifyUri(link) else null
        val audio = app.getSystemService(AudioManager::class.java)

        showAnchor(app)
        // stop whatever is playing, so "music active" below means the new playlist
        if (audio.isMusicActive) key(app, KeyEvent.KEYCODE_MEDIA_PAUSE)

        // open in the streaming app with autoplay hints (Spotify ":play", Amazon "do=play")
        val openUri = Uri.parse(
            when {
                spUri != null -> "$spUri:play"
                card.isAmazon -> amazonAutoplay(link)
                else -> link
            }
        )
        val opened = tryStart(activity, Intent(Intent.ACTION_VIEW, openUri).setPackage(pkg))
            || (spUri != null && tryStart(activity, Intent(Intent.ACTION_VIEW, Uri.parse(spUri)).setPackage(pkg)))
            || tryStart(activity, Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        if (!opened) { hideAnchor(app); return false }

        val id = ++runId
        val t0 = SystemClock.elapsedRealtime()
        var keySent = false
        val tick = object : Runnable {
            override fun run() {
                if (id != runId) return
                val elapsed = SystemClock.elapsedRealtime() - t0
                // music playing (no permission needed) -> back to the app
                if (audio.isMusicActive && elapsed > 2500) {
                    handler.postDelayed({ bringBack(app) }, 1500); return
                }
                // nothing after 5 s -> press play once
                if (!keySent && elapsed > 5000) { key(app, KeyEvent.KEYCODE_MEDIA_PLAY); keySent = true }
                if (elapsed < TIMEOUT_MS) handler.postDelayed(this, 700) else bringBack(app)
            }
        }
        handler.postDelayed(tick, 1200)
        return true
    }

    private fun tryStart(a: Activity, i: Intent): Boolean = try {
        a.startActivity(i); true
    } catch (e: Exception) {
        false
    }

    fun key(ctx: Context, code: Int) {
        val a = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        a.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        a.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /** Bring the app back to the front; retried a few times if it did not work. */
    fun bringBack(ctx: Context, attempt: Int = 0) {
        if (MainActivity.isFront) { hideAnchor(ctx); return }
        val i = Intent(ctx, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        try { ctx.startActivity(i) } catch (_: Exception) {}
        if (attempt < 3) handler.postDelayed({ bringBack(ctx, attempt + 1) }, 1500)
        else handler.postDelayed({ hideAnchor(ctx) }, 1500)
    }
}
