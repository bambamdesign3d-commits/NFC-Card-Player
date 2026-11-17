package de.bambamdesign3d.nfccardplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Manages Amazon Music cards. Also receives shared links from the
 * Amazon Music app (Share → NFC Card Player).
 */
class AmazonActivity : AppCompatActivity() {

    private lateinit var adapter: Adapter
    private lateinit var tvEmpty: TextView
    private lateinit var editor: CardEditor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(this)
        setContentView(R.layout.activity_amazon)
        UiUtil.hideSystemBars(this)
        tvEmpty = findViewById(R.id.tvEmpty)
        findViewById<TextView>(R.id.tvHeader).text = t("Streaming-Karten (Amazon Music / Spotify)", "Streaming cards (Amazon Music / Spotify)")
        findViewById<TextView>(R.id.tvHint).text = t(
            "Tipp: In Amazon Music oder Spotify bei einer Playlist auf Teilen tippen und „NFC Card Player“ wählen – Link, Name und Bild werden übernommen.",
            "Tip: In Amazon Music or Spotify tap Share on a playlist and choose “NFC Card Player” – link, name and picture are taken over."
        )
        tvEmpty.text = t("Noch keine Streaming-Karten angelegt", "No streaming cards yet")
        // same editor as in the main card list
        editor = CardEditor(this, onSaved = { refresh() }, onWrite = { writeCard(it) })
        adapter = Adapter(
            onEdit = { e -> CardStore.get(this, e.number)?.let { editor.show(it) } },
            onDelete = { confirmDelete(it) }
        )
        findViewById<RecyclerView>(R.id.rvAmazon).apply {
            layoutManager = LinearLayoutManager(this@AmazonActivity)
            adapter = this@AmazonActivity.adapter
        }
        findViewById<Button>(R.id.btnAdd).apply {
            text = t("+ Neuer Link", "+ New link")
            setOnClickListener { editor.show(null, Card(AmazonStore.nextFree(used()), source = SOURCE_AMAZON, link = "")) }
        }
        findViewById<Button>(R.id.btnClose).apply {
            text = t("Zurück", "Back")
            setOnClickListener { finish() }
        }
        findViewById<Button>(R.id.btnSetup).setOnClickListener { setupNextStep() }
        refresh()
        handleShare(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) UiUtil.hideSystemBars(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        updateSetup()
    }

    private fun updateSetup() {
        findViewById<TextView>(R.id.tvSetup).text = if (StreamStarter.canReturn(this))
            t("✓ Bereit: Karten starten die Playlist, danach kommt die App wieder nach vorne.",
              "✓ Ready: cards start the playlist, then the app comes back to the front.")
        else
            t("✗ Damit die App nach dem Start zurückkommt, fehlt „Über anderen Apps einblenden“ (Einstellungen → Berechtigungen).",
              "✗ For the app to come back after starting, “Display over other apps” is missing (Settings → Permissions).")
        findViewById<Button>(R.id.btnSetup).text = t("Einstellungen", "Settings")
    }

    private fun setupNextStep() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        intent.action = null
        val link = AmazonStore.extractLink(text)
        if (link == null) {
            toast(t("Kein Link im geteilten Text gefunden", "No link found in the shared text"))
            return
        }
        val name = AmazonStore.guessName(text).ifBlank { intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: "" }
        val existing = AmazonStore.all(this).firstOrNull { it.link == link }
        val card = existing?.let { CardStore.get(this, it.number) }
        if (card != null) editor.show(card)
        else editor.show(null, Card(AmazonStore.nextFree(used()), name.ifBlank { null }, AmazonStore.sourceFor(link), link = link))
    }

    private fun used(): Set<Int> = CardStore.load(this).map { it.number }.toSet()

    private fun writeCard(nr: Int) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_WRITE, nr)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    private fun confirmDelete(e: AmazonStore.Entry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(t("Karte ${e.number} löschen?", "Delete card ${e.number}?"))
            .setMessage(e.name.ifBlank { e.link })
            .setPositiveButton(t("Löschen", "Delete")) { _, _ -> AmazonStore.remove(this, e.number); refresh() }
            .setNegativeButton(t("Abbrechen", "Cancel"), null)
            .show()
    }

    private fun refresh() {
        val list = AmazonStore.all(this)
        adapter.submit(list)
        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    class Adapter(
        private val onEdit: (AmazonStore.Entry) -> Unit,
        private val onDelete: (AmazonStore.Entry) -> Unit
    ) : RecyclerView.Adapter<Adapter.VH>() {
        private var items: List<AmazonStore.Entry> = emptyList()

        fun submit(l: List<AmazonStore.Entry>) { items = l; notifyDataSetChanged() }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val nr: TextView = v.findViewById(R.id.tvNr)
            val name: TextView = v.findViewById(R.id.tvName)
            val info: TextView = v.findViewById(R.id.tvInfo)
            val edit: Button = v.findViewById(R.id.btnEdit)
            val thumb: android.widget.ImageView = v.findViewById(R.id.ivThumb)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_karte, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val e = items[pos]
            h.nr.text = "%02d".format(e.number)
            h.name.text = e.name.ifBlank { if (e.source == SOURCE_SPOTIFY) "Spotify" else "Amazon Music" }
            h.info.text = (if (e.source == SOURCE_SPOTIFY) "Spotify · " else "Amazon · ") + e.link + "\n" + t("lange drücken = löschen", "long press = delete")
            h.edit.text = t("Bearbeiten", "Edit")
            h.edit.setOnClickListener { onEdit(e) }
            val b = CoverStore.get(h.itemView.context, e.number)
            if (b != null) h.thumb.setImageBitmap(b) else h.thumb.setImageResource(R.drawable.ic_cover_placeholder)
            h.itemView.setOnClickListener { onEdit(e) }
            h.itemView.setOnLongClickListener { onDelete(e); true }
        }
    }
}
