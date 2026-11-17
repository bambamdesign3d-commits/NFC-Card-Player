package de.bambamdesign3d.nfccardplayer

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Spielt die Musik ab. Die MediaSession sorgt dafür, dass Play/Pause am
 * Bluetooth-Speaker und im Sperrbildschirm funktioniert.
 * Speichert bei Hörbüchern laufend Folge und Position.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())

    private val saveTicker = object : Runnable {
        override fun run() {
            saveProgress()
            handler.postDelayed(this, 5000)
        }
    }

    companion object {
        @Volatile
        var instance: PlaybackService? = null
    }

    fun isPlaying(): Boolean = ::player.isInitialized && player.isPlaying

    fun setVolumeFactor(f: Float) {
        if (::player.isInitialized) player.volume = f.coerceIn(0f, 1f)
    }

    fun hasItems(): Boolean = ::player.isInitialized && player.mediaItemCount > 0

    fun pausePlayer() {
        if (::player.isInitialized) player.pause()
    }

    fun playPlayer() {
        if (::player.isInitialized && player.mediaItemCount > 0) player.play()
    }

    fun pauseAndRestoreVolume() {
        if (!::player.isInitialized) return
        player.pause()
        player.volume = 1f
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                saveProgress()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                saveProgress()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    // fertig gehört -> nächstes Mal wieder von vorn
                    val (nr, _) = StateStore.parse(player.currentMediaItem?.mediaId) ?: return
                    StateStore.clear(this@PlaybackService, nr)
                }
            }
        })

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .build()

        handler.postDelayed(saveTicker, 5000)
    }

    private fun saveProgress() {
        if (!::player.isInitialized) return
        if (player.playbackState == Player.STATE_ENDED) return
        val (nr, file) = StateStore.parse(player.currentMediaItem?.mediaId) ?: return
        StateStore.save(this, nr, file, player.currentPosition)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = mediaSession?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(saveTicker)
        saveProgress()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
