package de.bambamdesign3d.nfccardplayer

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Caps the media volume (also for Bluetooth speakers) without any permission:
 * whenever the volume changes above the limit, it is set back to the limit.
 */
object VolumeLimit {
    private var observer: ContentObserver? = null

    fun maxIndex(ctx: Context): Int {
        val am = ctx.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val pct = AppPrefs.volumeLimit(ctx).coerceIn(10, 100)
        return ((max * pct + 99) / 100).coerceIn(1, max)
    }

    fun apply(ctx: Context) {
        if (AppPrefs.volumeLimit(ctx) >= 100) return
        val am = ctx.getSystemService(AudioManager::class.java)
        val limit = maxIndex(ctx)
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) > limit)
            try { am.setStreamVolume(AudioManager.STREAM_MUSIC, limit, 0) } catch (_: Exception) {}
    }

    fun register(ctx: Context) {
        if (observer != null) return
        val app = ctx.applicationContext
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = apply(app)
        }.also { app.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, it) }
        apply(app)
    }

    fun unregister(ctx: Context) {
        observer?.let { ctx.applicationContext.contentResolver.unregisterContentObserver(it) }
        observer = null
    }
}
