package de.bambamdesign3d.nfccardplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Online picture search for a card: uses the public iTunes search
 * (album and audiobook covers, no account needed). The chosen picture is
 * downloaded once and stored in the app.
 */
class ImageSearch(private val a: AppCompatActivity, private val onSaved: () -> Unit) {

    data class Hit(val title: String, val thumb: String, val big: String)

    companion object {
        fun search(query: String): List<Hit> {
            val q = URLEncoder.encode(query, "UTF-8")
            val urls = listOf(
                "https://itunes.apple.com/search?term=$q&media=music&entity=album&country=de&limit=24",
                "https://itunes.apple.com/search?term=$q&media=audiobook&country=de&limit=12"
            )
            val hits = mutableListOf<Hit>()
            for (u in urls) {
                try {
                    val con = (URL(u).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000; readTimeout = 8000
                    }
                    val txt = con.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    con.disconnect()
                    val arr = JSONObject(txt).optJSONArray("results") ?: continue
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val art = o.optString("artworkUrl100")
                        if (art.isBlank()) continue
                        val title = o.optString("collectionName").ifBlank { o.optString("trackName") }
                        hits += Hit(title, art, art.replace("100x100bb", "600x600bb"))
                    }
                } catch (_: Exception) {
                }
            }
            return hits.distinctBy { it.big }
        }

        private fun loadThumb(url: String): Bitmap? = try {
            val con = URL(url).openConnection() as HttpURLConnection
            con.connectTimeout = 8000; con.readTimeout = 8000
            con.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
    }

    private val dp = a.resources.displayMetrics.density

    fun show(card: Card) {
        val pad = (12 * dp).toInt()
        val query = card.name ?: card.folder?.substringAfterLast("/") ?: ""
        val etQuery = EditText(a).apply {
            setText(query)
            hint = t("Suchbegriff", "Search term")
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine()
        }
        val btnSearch = Button(a).apply { text = t("Suchen", "Search") }
        val info = TextView(a)
        val grid = GridLayout(a).apply { columnCount = 3 }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(etQuery)
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(btnSearch, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
            addView(info)
            addView(grid)
        }
        lateinit var dialog: AlertDialog
        val size = (96 * dp).toInt()

        fun run() {
            val q = etQuery.text.toString().trim()
            if (q.isEmpty()) return
            grid.removeAllViews()
            info.text = t("Suche …", "Searching …")
            Thread {
                val hits = search(q)
                a.runOnUiThread {
                    info.text = if (hits.isEmpty()) t("Nichts gefunden – anderen Suchbegriff probieren.", "Nothing found – try another search term.")
                    else t("Bild antippen zum Übernehmen", "Tap a picture to use it")
                    for (h in hits) {
                        val iv = ImageView(a).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            contentDescription = h.title
                            setImageResource(R.drawable.ic_cover_placeholder)
                            setOnClickListener {
                                info.text = t("Wird gespeichert …", "Saving …")
                                Thread {
                                    val ok = CoverStore.download(a, card.number, h.big) || CoverStore.download(a, card.number, h.thumb)
                                    a.runOnUiThread {
                                        if (ok) {
                                            Toast.makeText(a, t("Bild gespeichert", "Picture saved"), Toast.LENGTH_SHORT).show()
                                            onSaved()
                                            dialog.dismiss()
                                        } else info.text = t("Download fehlgeschlagen", "Download failed")
                                    }
                                }.start()
                            }
                        }
                        grid.addView(iv, GridLayout.LayoutParams().apply {
                            width = size; height = size
                            setMargins(4, 4, 4, 4)
                        })
                        Thread { loadThumb(h.thumb)?.let { b -> a.runOnUiThread { iv.setImageBitmap(b) } } }.start()
                    }
                }
            }.start()
        }

        btnSearch.setOnClickListener { run() }
        etQuery.setOnEditorActionListener { _, _, _ -> run(); true }
        dialog = MaterialAlertDialogBuilder(a)
            .setTitle(t("Bild online suchen – Karte ${card.number}", "Search picture online – card ${card.number}"))
            .setView(ScrollView(a).apply { addView(box) })
            .setNegativeButton(t("Schließen", "Close"), null)
            .create()
        dialog.show()
        if (query.isNotBlank()) run()
    }
}
