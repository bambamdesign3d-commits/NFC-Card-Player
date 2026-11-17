package de.bambamdesign3d.nfccardplayer

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Short spoken announcements ("Timer on", "Stoptanz aus" …) via the phone's offline text-to-speech. */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun say(ctx: Context, text: String) {
        val engine = tts
        if (engine != null && ready) {
            speakNow(engine, text)
            return
        }
        pending = text
        if (engine == null) {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                val p = pending
                pending = null
                if (ready && p != null) tts?.let { speakNow(it, p) }
            }
        }
    }

    private fun speakNow(engine: TextToSpeech, text: String) {
        engine.language = if (I18n.en) Locale.ENGLISH else Locale.GERMAN
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cardmusik")
    }
}
