package de.bambamdesign3d.nfccardplayer

import android.content.Context
import android.graphics.Typeface
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * "Remote control & sync": share this box in the Wi-Fi, or connect to another box
 * to control it and to copy cards + settings in either direction.
 */
class RemoteActivity : AppCompatActivity() {

    private lateinit var box: LinearLayout
    private val dp by lazy { resources.displayMetrics.density }
    private val handler = Handler(Looper.getMainLooper())

    // client side
    private val client by lazy { getSharedPreferences("remote_client", Context.MODE_PRIVATE) }
    private lateinit var found: LinearLayout
    private lateinit var knownBox: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var tvRemote: TextView
    private lateinit var cbCards: CheckBox
    private lateinit var cbCovers: CheckBox
    private lateinit var cbSettings: CheckBox
    private lateinit var cbPlayer: CheckBox
    private var host: String? = null
    private var pin: String? = null

    // server side
    private lateinit var tvShare: TextView

    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * dp).toInt()
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        setContentView(ScrollView(this).apply { addView(box) })
        title = t("Fernbedienung & Sync", "Remote control & sync")

        box.addView(TextView(this).apply {
            textSize = 13f
            text = t(
                "Zwei Handys mit NFC Card Player im selben WLAN: Die Box gibt sich frei, das zweite Handy verbindet sich mit der PIN der Box. Danach kann es die Box fernbedienen und alle Karten und Einstellungen in beide Richtungen übertragen.",
                "Two phones with NFC Card Player in the same Wi-Fi: the box shares itself, the second phone connects with the box's PIN. It can then control the box and copy all cards and settings in either direction."
            )
        })

        // ------------------------------------------------ 1. share this phone
        header(t("Dieses Gerät", "This device"))
        val sw = MaterialSwitch(this).apply {
            text = t("Dies ist ein ferngesteuertes Gerät (Box)", "This is a remote controlled device (box)")
            isChecked = Remote.enabled(this@RemoteActivity)
            setOnCheckedChangeListener { _, c -> Remote.setEnabled(this@RemoteActivity, c); updateShare() }
        }
        box.addView(sw)
        tvShare = TextView(this).apply { textSize = 14f; setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt()) }
        box.addView(tvShare)
        row(
            button(t("Name ändern", "Change name")) { changeName() },
            button(t("Neue PIN", "New PIN")) { Remote.newPin(this); updateShare() }
        )

        // ------------------------------------------------ 2. connect to a box
        header(t("Mit einer Box verbinden", "Connect to a box"))
        box.addView(TextView(this).apply {
            textSize = 12f
            text = t("Verbundene Boxen werden gespeichert. Aktiv ist immer eine (✓). Im Hauptbildschirm erscheint dann der Knopf „Remote-Modus“: Karten aus der Liste spielen direkt auf der Box, die Player-Leiste zeigt die Box. 🗑 = Box entfernen.",
                "Connected boxes are saved. One is always active (✓). The main screen then shows the “Remote mode” button: cards from the list play directly on the box, the player bar shows the box. 🗑 = remove box.")
        })
        knownBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(knownBox)
        found = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(found)
        box.addView(button(t("Erneut suchen", "Search again")) { startDiscovery() })

        // ------------------------------------------------ connected panel
        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        box.addView(panel)
        tvRemote = TextView(this).apply { textSize = 15f; setTypeface(null, Typeface.BOLD); setPadding(0, pad, 0, pad / 2) }
        panel.addView(tvRemote)
        rowIn(panel,
            button("⏮") { cmd("prev") },
            button("⏯") { cmd("toggle") },
            button("⏭") { cmd("next") },
            button(t("Leiser", "Vol −")) { cmd("voldown") },
            button(t("Lauter", "Vol +")) { cmd("volup") }
        )
        val etCard = EditText(this).apply { hint = t("Kartennummer", "Card number"); inputType = InputType.TYPE_CLASS_NUMBER }
        val cardRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cardRow.addView(etCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        cardRow.addView(button(t("Karte starten", "Start card")) {
            etCard.text.toString().toIntOrNull()?.let { cmd("card", it) }
        })
        panel.addView(cardRow)
        panel.addView(subHeader(t("Karten & Einstellungen übertragen", "Transfer cards & settings")))
        fun cb(txt: String, on: Boolean) = CheckBox(this).apply { text = txt; isChecked = on }.also { panel.addView(it) }
        cbCards = cb(t("Alle Karten", "All cards"), true)
        cbCovers = cb(t("Kartenbilder", "Card pictures"), true)
        cbSettings = cb(t("Einstellungen (Zeitschaltung, Optionen, Lautstärke …)", "Settings (schedule, options, volume …)"), true)
        cbPlayer = cb(t("Player-Leiste (Größe, Position, Farben)", "Player bar (size, position, colours)"), false)
        panel.addView(button(t("Von der Box holen  (Box → dieses Handy)", "Fetch from the box  (box → this phone)")) { confirmPull() })
        panel.addView(button(t("An die Box senden  (dieses Handy → Box)", "Send to the box  (this phone → box)")) { confirmPush() })
        panel.addView(TextView(this).apply {
            textSize = 12f
            text = t(
                "Die ausgewählten Teile ersetzen die vorhandenen. Nie übertragen: Berechtigungen, gespeicherte Hörstellen und die Fernbedienungs-Einstellungen. Ordner-Karten brauchen die MP3s auf beiden Handys im gleichen Ordner.",
                "The selected parts replace the existing ones. Never transferred: permissions, saved listening positions and the remote settings. Folder cards need the MP3s on both phones in the same folder."
            )
        })

        box.addView(button(t("Zurück", "Back")) { finish() }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = (16 * dp).toInt()
        })

        updateShare()
        refreshKnown()
        Remote.active(this)?.let { b -> connect(b.host, b.pin) }
        startDiscovery()
    }

    private fun refreshKnown() {
        knownBox.removeAllViews()
        val list = Remote.known(this)
        if (list.isEmpty()) return
        knownBox.addView(subHeader(t("Gespeicherte Boxen", "Saved boxes")))
        val active = Remote.active(this)?.host
        list.forEach { b ->
            val rowV = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val etP = EditText(this).apply {
                hint = "PIN"; setText(b.pin); inputType = InputType.TYPE_CLASS_NUMBER
                textSize = 15f; gravity = Gravity.CENTER
            }
            rowV.addView(button((if (b.host == active) "✓ " else "    ") + "📻 ${b.name}") {
                Remote.setActive(this, b.host)
                refreshKnown()
                connect(b.host, etP.text.toString().trim())
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            // PIN right before the bin symbol (changed PIN is saved with the next connection)
            rowV.addView(etP, LinearLayout.LayoutParams((80 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (6 * dp).toInt()
            })
            rowV.addView(button("🗑") {
                MaterialAlertDialogBuilder(this@RemoteActivity)
                    .setTitle(t("Box entfernen?", "Remove box?"))
                    .setMessage("${b.name} (${b.host})")
                    .setPositiveButton(t("Entfernen", "Remove")) { _, _ ->
                        Remote.removeBox(this@RemoteActivity, b.host)
                        if (host == b.host) { host = null; pin = null; panel.visibility = View.GONE; handler.removeCallbacks(poll) }
                        refreshKnown()
                    }
                    .setNegativeButton(t("Abbrechen", "Cancel"), null)
                    .show()
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (6 * dp).toInt()
            })
            knownBox.addView(rowV)
        }
    }

    override fun onDestroy() {
        stopDiscovery()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) UiUtil.hideSystemBars(this)
    }

    // ------------------------------------------------ share

    private fun updateShare() {
        val on = Remote.enabled(this)
        if (on) Remote.start(this)
        val ip = Remote.ip()
        tvShare.text = if (!on) t("Aus – dieses Handy ist nur Fernbedienung und für andere nicht sichtbar.", "Off – this phone is only a remote control and not visible to others.")
        else t("Name: ${Remote.name(this)}\nAdresse: ${ip ?: "kein WLAN"}\nPIN: ${Remote.pin(this)}",
               "Name: ${Remote.name(this)}\nAddress: ${ip ?: "no Wi-Fi"}\nPIN: ${Remote.pin(this)}")
    }

    private fun changeName() {
        val et = EditText(this).apply { setText(Remote.name(this@RemoteActivity)) }
        MaterialAlertDialogBuilder(this)
            .setTitle(t("Name der Box", "Box name"))
            .setView(et)
            .setPositiveButton(t("Speichern", "Save")) { _, _ ->
                Remote.setName(this, et.text.toString())
                if (Remote.enabled(this)) { Remote.stop(this); Remote.start(this) }
                updateShare()
            }
            .setNegativeButton(t("Abbrechen", "Cancel"), null)
            .show()
    }

    // ------------------------------------------------ discovery

    private fun startDiscovery() {
        stopDiscovery()
        found.removeAllViews()
        found.addView(TextView(this).apply { textSize = 13f; text = t("Suche Boxen im WLAN …", "Searching for boxes in the Wi-Fi …") })
        val m = getSystemService(NsdManager::class.java) ?: return
        nsd = m
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                if (i.serviceName == Remote.name(this@RemoteActivity) && Remote.enabled(this@RemoteActivity)) return
                @Suppress("DEPRECATION")
                m.resolveService(i, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, e: Int) {}
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        val h = r.host?.hostAddress ?: return
                        if (h == Remote.ip() && Remote.enabled(this@RemoteActivity)) return
                        runOnUiThread { addFound(r.serviceName, h) }
                    }
                })
            }
        }
        try {
            m.discoverServices(Remote.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
            discovery = l
            handler.postDelayed({ stopDiscovery() }, 15_000)
        } catch (_: Exception) {
        }
    }

    private fun stopDiscovery() {
        discovery?.let { try { nsd?.stopServiceDiscovery(it) } catch (_: Exception) {} }
        discovery = null
    }

    private fun addFound(name: String, h: String) {
        // already saved: only update its address if it changed, no second entry
        Remote.known(this).firstOrNull { it.name == name || it.host == h }?.let { k ->
            if (k.host != h) {
                val prevActive = Remote.active(this)?.host
                val wasActive = prevActive == k.host
                Remote.removeBox(this, k.host)
                Remote.saveBox(this, k.copy(host = h))          // makes it active …
                if (!wasActive && prevActive != null) Remote.setActive(this, prevActive) // … keep the previous one active
                refreshKnown()
            }
            return
        }
        if (found.findViewWithTag<View>(h) != null) return
        if (found.childCount == 1 && found.getChildAt(0) !is MaterialButton) found.removeAllViews()
        found.addView(button("➕ 📻 $name") { askPin(name, h) }.apply { tag = h })
    }

    /** New box found: ask for its PIN, then connect (and save). */
    private fun askPin(name: String, h: String) {
        val et = EditText(this).apply { hint = "PIN"; inputType = InputType.TYPE_CLASS_NUMBER; gravity = Gravity.CENTER }
        MaterialAlertDialogBuilder(this)
            .setTitle(t("PIN von „$name“", "PIN of “$name”"))
            .setMessage(t("Die PIN steht auf der Box unter Menü → Fernbedienung & Sync.", "The PIN is shown on the box under menu → Remote control & sync."))
            .setView(et)
            .setPositiveButton(t("Verbinden", "Connect")) { _, _ -> connect(h, et.text.toString().trim()) }
            .setNegativeButton(t("Abbrechen", "Cancel"), null)
            .show()
    }

    // ------------------------------------------------ client

    private fun connect(h: String, p: String) {
        if (p.isEmpty()) { toast(t("PIN eingeben", "Enter the PIN")); return }
        tvRemote.text = t("Verbinde …", "Connecting …")
        panel.visibility = View.VISIBLE
        thread {
            val r = try { Remote.call(h, p, JSONObject().put("op", "info")) } catch (e: Exception) { null }
            runOnUiThread {
                when {
                    r == null -> { panel.visibility = View.GONE; toast(t("Box nicht erreichbar – gleiches WLAN? Freigabe an?", "Box not reachable – same Wi-Fi? Sharing on?")) }
                    !r.optBoolean("ok") -> { panel.visibility = View.GONE; toast(t("Falsche PIN", "Wrong PIN")) }
                    else -> {
                        host = h; pin = p
                        client.edit().putString("host", h).putString("pin", p).apply()
                        Remote.saveBox(this, Remote.Box(r.optString("name").ifBlank { h }, h, p))
                        refreshKnown()
                        showInfo(r)
                        handler.removeCallbacks(poll); handler.postDelayed(poll, 3000)
                    }
                }
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            val h = host ?: return; val p = pin ?: return
            thread {
                val r = try { Remote.call(h, p, JSONObject().put("op", "info"), 4000) } catch (e: Exception) { null }
                runOnUiThread { if (r != null) showInfo(r) else tvRemote.text = t("Verbindung unterbrochen …", "Connection lost …") }
            }
            handler.postDelayed(this, 3000)
        }
    }

    private fun showInfo(r: JSONObject) {
        val name = r.optString("name")
        val title = r.optString("title").ifBlank { "–" }
        val state = if (r.optBoolean("playing")) "▶" else "⏸"
        tvRemote.text = "📻 $name\n$state  $title"
    }

    private fun cmd(action: String, nr: Int = -1) {
        val h = host ?: return; val p = pin ?: return
        thread {
            val ok = try { Remote.call(h, p, JSONObject().put("op", "cmd").put("action", action).put("nr", nr)).optBoolean("ok") } catch (e: Exception) { false }
            runOnUiThread { if (!ok) toast(t("Befehl nicht angekommen", "Command not delivered")) }
        }
    }

    /** Only the selected parts of a data set. */
    private fun pick(d: JSONObject): JSONObject {
        val o = JSONObject()
        if (cbCards.isChecked) o.put("cards", d.optString("cards"))
        if (cbSettings.isChecked) d.optJSONObject("app")?.let { o.put("app", it) }
        if (cbPlayer.isChecked) d.optJSONObject("player")?.let { o.put("player", it) }
        d.optJSONArray("covers")?.let { o.put("covers", it) }
        return o
    }

    private fun partsText(): String? {
        val l = listOfNotNull(
            if (cbCards.isChecked) t("Karten", "cards") else null,
            if (cbCovers.isChecked) t("Bilder", "pictures") else null,
            if (cbSettings.isChecked) t("Einstellungen", "settings") else null,
            if (cbPlayer.isChecked) t("Player-Leiste", "player bar") else null
        )
        if (l.isEmpty()) { toast(t("Bitte mindestens einen Punkt auswählen", "Please select at least one item")); return null }
        return l.joinToString(", ")
    }

    private fun confirmPull() {
        val parts = partsText() ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(t("Von der Box holen?", "Fetch from the box?"))
            .setMessage(t("Auf DIESEM Handy werden ersetzt: $parts", "Replaced on THIS phone: $parts"))
            .setPositiveButton(t("Holen", "Fetch")) { _, _ -> pull() }
            .setNegativeButton(t("Abbrechen", "Cancel"), null)
            .show()
    }

    private fun confirmPush() {
        val parts = partsText() ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(t("An die Box senden?", "Send to the box?"))
            .setMessage(t("Auf der BOX werden ersetzt: $parts", "Replaced on the BOX: $parts"))
            .setPositiveButton(t("Senden", "Send")) { _, _ -> push() }
            .setNegativeButton(t("Abbrechen", "Cancel"), null)
            .show()
    }

    private fun pull() {
        val h = host ?: return; val p = pin ?: return
        val withCovers = cbCovers.isChecked
        toast(t("Hole Daten …", "Fetching …"))
        thread {
            val msg = try {
                val r = Remote.call(h, p, JSONObject().put("op", "export"), 20_000)
                val data = pick(r.getJSONObject("data"))
                val covers = JSONObject()
                if (withCovers) {
                    val nrs = data.optJSONArray("covers")
                    for (i in 0 until (nrs?.length() ?: 0)) {
                        val nr = nrs!!.getInt(i)
                        Remote.call(h, p, JSONObject().put("op", "cover").put("nr", nr), 15_000).optString("img")
                            .takeIf { it.isNotBlank() }?.let { covers.put(nr.toString(), it) }
                    }
                }
                Remote.importData(this, data)
                Remote.putCovers(this, covers)
                MainActivity.current?.let { a -> a.runOnUiThread { a.onRemoteImported() } }
                t("Übernommen: Karten und Einstellungen der Box", "Done: cards and settings of the box")
            } catch (e: Exception) {
                t("Fehler beim Holen: ${e.message}", "Fetch failed: ${e.message}")
            }
            runOnUiThread { toast(msg) }
        }
    }

    private fun push() {
        val h = host ?: return; val p = pin ?: return
        val withCovers = cbCovers.isChecked
        toast(t("Sende Daten …", "Sending …"))
        thread {
            val msg = try {
                val data = pick(Remote.export(this))
                val covers = JSONObject()
                if (withCovers) {
                    val nrs = data.optJSONArray("covers")
                    for (i in 0 until (nrs?.length() ?: 0)) {
                        val nr = nrs!!.getInt(i)
                        CoverStore.bytes(this, nr)?.let { covers.put(nr.toString(), Base64.encodeToString(it, Base64.NO_WRAP)) }
                    }
                }
                val r = Remote.call(h, p, JSONObject().put("op", "import").put("data", data).put("covers", covers), 60_000)
                if (r.optBoolean("ok")) t("Gesendet: Die Box hat alles übernommen", "Sent: the box took over everything")
                else t("Box hat abgelehnt", "Box refused")
            } catch (e: Exception) {
                t("Fehler beim Senden: ${e.message}", "Send failed: ${e.message}")
            }
            runOnUiThread { toast(msg) }
        }
    }

    // ------------------------------------------------ ui helpers

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun header(s: String) = box.addView(TextView(this).apply {
        text = s; textSize = 18f; setTypeface(null, Typeface.BOLD)
        setPadding(0, (20 * dp).toInt(), 0, (6 * dp).toInt())
    })

    private fun subHeader(s: String) = TextView(this).apply {
        text = s; textSize = 15f; setTypeface(null, Typeface.BOLD)
        setPadding(0, (14 * dp).toInt(), 0, (4 * dp).toInt())
    }

    private fun button(s: String, onClick: () -> Unit) = MaterialButton(
        this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
    ).apply {
        text = s; isAllCaps = false
        minWidth = 0; minimumWidth = 0
        setPadding((10 * dp).toInt(), paddingTop, (10 * dp).toInt(), paddingBottom)
        setOnClickListener { onClick() }
    }

    private fun row(vararg v: View) = rowIn(box, *v)

    private fun rowIn(parent: LinearLayout, vararg v: View) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        v.forEachIndexed { i, x ->
            r.addView(x, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = (6 * dp).toInt()
            })
        }
        parent.addView(r)
    }
}
