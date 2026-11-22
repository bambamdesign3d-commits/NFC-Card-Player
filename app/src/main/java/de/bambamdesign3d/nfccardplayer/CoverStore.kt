package de.bambamdesign3d.nfccardplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bilder je Karte, einmalig ermittelt und in der App gespeichert (danach offline):
 * - aus dem eingebetteten Cover der eigenen MP3-Dateien
 * - bei Amazon: einmalig von der Amazon-Seite bzw. aus Amazon Music
 * - oder manuell aus der Galerie gewählt
 */
object CoverStore {
    private const val MAX = 400
    private val cache = HashMap<Int, Bitmap?>()

    private fun dir(ctx: Context) = File(ctx.filesDir, "covers").apply { mkdirs() }
    private fun file(ctx: Context, nr: Int) = File(dir(ctx), "$nr.jpg")
    private fun triedPrefs(ctx: Context) = ctx.getSharedPreferences("covers_tried", Context.MODE_PRIVATE)

    fun has(ctx: Context, nr: Int) = file(ctx, nr).exists()

    /** Raw JPEG of a card picture (for remote sync). */
    fun bytes(ctx: Context, nr: Int): ByteArray? = file(ctx, nr).takeIf { it.exists() }?.readBytes()

    @Synchronized
    fun putBytes(ctx: Context, nr: Int, data: ByteArray) {
        file(ctx, nr).writeBytes(data)
        cache.remove(nr)
    }

    @Synchronized
    fun get(ctx: Context, nr: Int): Bitmap? {
        if (cache.containsKey(nr)) return cache[nr]
        val f = file(ctx, nr)
        val b = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
        cache[nr] = b
        return b
    }

    @Synchronized
    fun save(ctx: Context, nr: Int, bmp: Bitmap) {
        val scaled = scale(bmp)
        file(ctx, nr).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        cache[nr] = scaled
    }

    @Synchronized
    fun remove(ctx: Context, nr: Int) {
        file(ctx, nr).delete()
        cache.remove(nr)
        triedPrefs(ctx).edit().putBoolean("t$nr", true).apply() // nicht automatisch neu suchen
    }

    @Synchronized
    fun move(ctx: Context, from: Int, to: Int) {
        val f = file(ctx, from)
        if (f.exists()) f.renameTo(file(ctx, to))
        cache.remove(from); cache.remove(to)
    }

    fun resetTried(ctx: Context, nr: Int) = triedPrefs(ctx).edit().remove("t$nr").apply()

    private fun scale(b: Bitmap): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= MAX) return b
        val f = MAX.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
    }

    private fun decodeBytes(bytes: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= MAX) s *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    /** Für alle Karten ohne Bild einmalig das Cover aus den MP3s holen. Gibt true zurück, wenn neue Bilder dazukamen. */
    fun ensureFromFiles(ctx: Context, cards: List<Card>): Boolean {
        var neu = false
        val tried = triedPrefs(ctx)
        for (k in cards) {
            if (!k.usesFolder || has(ctx, k.number) || tried.getBoolean("t${k.number}", false)) continue
            val files = k.files()
            val first = if (k.mode == MODE_SINGLE) files.filter { it.name == k.file } + files else files
            val bmp = first.take(8).firstNotNullOfOrNull { embedded(it) }
            if (bmp != null) {
                save(ctx, k.number, bmp)
                neu = true
            }
            tried.edit().putBoolean("t${k.number}", true).apply()
        }
        return neu
    }

    private fun embedded(f: File): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            r.embeddedPicture?.let { decodeBytes(it) }
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /** Manuell gewähltes Bild aus der Galerie übernehmen. */
    fun setFromUri(ctx: Context, nr: Int, uri: Uri): Boolean = try {
        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        decodeBytes(bytes)?.let { save(ctx, nr, it); true } ?: false
    } catch (e: Exception) {
        false
    }

    /** Download a picture once (e.g. playlist cover) and store it for the card. */
    fun download(ctx: Context, nr: Int, url: String): Boolean =
        loadUrl(url)?.let { save(ctx, nr, it); true } ?: false

    /** Load a picture from the internet (null on error). */
    fun loadUrl(url: String): Bitmap? = try {
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        val bytes = con.inputStream.use { it.readBytes() }
        con.disconnect()
        decodeBytes(bytes)
    } catch (e: Exception) {
        null
    }
}
