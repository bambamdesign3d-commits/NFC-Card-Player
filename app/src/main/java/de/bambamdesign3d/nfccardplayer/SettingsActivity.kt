package de.bambamdesign3d.nfccardplayer

import android.Manifest
import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Settings, structured:
 * 1. language  2. functions (switches only usable when their permission is granted)
 * 3. permissions (✓/✗, details behind "?") incl. lock screen  4. display schedule  5. box & volume  6. licenses
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var box: LinearLayout
    private val dp by lazy { resources.displayMetrics.density }
    private val refreshers = mutableListOf<() -> Unit>()

    private val red = Color.rgb(0xC6, 0x28, 0x28)
    private val green = Color.rgb(0x2E, 0x7D, 0x32)

    // ------------------------------------------------ permissions (name + check)

    private inner class Perm(val name: String, val granted: () -> Boolean)

    private val pStorage by lazy { Perm(t("Zugriff auf alle Dateien", "Access to all files")) { hasStorage() } }
    private val pOverlay by lazy { Perm(t("Über anderen Apps einblenden", "Display over other apps")) { Settings.canDrawOverlays(this) } }
    private val pBattery by lazy {
        Perm(t("Akku-Optimierung aus", "Battery optimisation off")) {
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        }
    }
    private val pAdmin by lazy { Perm(t("Display ausschalten (Geräteadministrator)", "Turn display off (device admin)")) { Schedule.isAdmin(this) } }
    private val pExact by lazy { Perm(t("Genaue Zeiten (Wecker)", "Exact times (alarms)")) { Schedule.canExact(this) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(this)
        val pad = (16 * dp).toInt()
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(box) })
        UiUtil.hideSystemBars(this)

        // ------------------------------------------------ 1. language
        header("Sprache / Language")
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbDe = RadioButton(this).apply { text = "Deutsch"; id = View.generateViewId() }
        val rbEn = RadioButton(this).apply { text = "English"; id = View.generateViewId() }
        rg.addView(rbDe); rg.addView(rbEn)
        rg.check(if (I18n.en) rbEn.id else rbDe.id)
        rg.setOnCheckedChangeListener { _, id ->
            val lang = if (id == rbEn.id) "en" else "de"
            if (lang != AppPrefs.language(this)) {
                AppPrefs.setLanguage(this, lang)
                I18n.init(this)
                recreate()
            }
        }
        box.addView(rg)

        // ------------------------------------------------ 2. functions
        header(t("Funktionen", "Functions"))
        switchRow(t("App beim Einschalten des Handys starten", "Start the app when the phone is switched on"),
            "autostart", AppPrefs.autostart(this), listOf(pOverlay))
        switchRow(t("Nach Autostart / Zeitschaltung direkt Box-Modus", "Box mode right after autostart / schedule"),
            "startBox", AppPrefs.startBox(this), emptyList())
        switchRow(t("Nach 30 s ohne Bedienung Box-Modus (dunkel nach weiteren 15 s)", "Box mode after 30 s without use (dark after another 15 s)"),
            "autoBox", AppPrefs.autoBox(this), emptyList())
        switchRow(t("Beim Einschalten des Displays über dem Sperrbildschirm zeigen", "Show above the lock screen when the display turns on"),
            "overLock", AppPrefs.overLock(this), listOf(pOverlay),
            help = t(
                "Die App erscheint beim Einschalten sofort über dem Sperrbildschirm und wischt eine einfache Wischsperre weg.\n\n⚠ Sicherheit: Wer das Handy in die Hand nimmt, kann die App ohne Entsperren bedienen – auch die Einstellungen. Mit PIN/Muster bleibt der Rest des Handys geschützt, NFC liest dann aber erst nach dem Entsperren.",
                "The app appears above the lock screen as soon as the display turns on and dismisses a simple swipe lock.\n\n⚠ Security: anyone holding the phone can use the app without unlocking – including the settings. With a PIN/pattern the rest of the phone stays protected, but NFC only reads after unlocking."
            ))

        // ------------------------------------------------ 3. permissions (incl. lock screen)
        header(t("Berechtigungen", "Permissions"), help = t(
            "✓ = erteilt, ✗ = fehlt. Mit „?“ siehst du, wofür die Berechtigung gebraucht wird, was ohne sie fehlt und welche Sicherheitsrisiken sie hat.",
            "✓ = granted, ✗ = missing. “?” shows what the permission is for, what is missing without it and its security risks."
        ))
        permRow(pStorage, { requestStorage() }, t(
            "Wofür: Musikordner abspielen, Bilder aus MP3s lesen, cards.json importieren/exportieren.\n\nOhne: Ordner-Karten bleiben stumm („keine Dateien gefunden“), kein Import/Export.\n\n⚠ Sicherheit: Die App könnte alle Dateien im gemeinsamen Speicher lesen und ändern (Fotos, Dokumente, Downloads). NFC Card Player liest nur Audiodateien und Bilder aus den gewählten Ordnern und schreibt nur die Export-Datei.",
            "For: playing music folders, reading pictures from MP3s, importing/exporting cards.json.\n\nWithout: folder cards stay silent (“no files found”), no import/export.\n\n⚠ Security: the app could read and modify all files in shared storage (photos, documents, downloads). NFC Card Player only reads audio files and pictures from the chosen folders and only writes the export file."
        ))
        permRow(pOverlay, { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }, t(
            "Wofür: Autostart, Display-Zeitschaltung, Rückkehr aus Amazon Music/Spotify, App über dem Sperrbildschirm.\n\nOhne: Diese Schalter sind gesperrt; die App startet nicht von selbst und kommt nach Amazon/Spotify nicht zurück.\n\n⚠ Sicherheit: Apps könnten damit Fenster über andere Apps legen und Eingaben abfangen („Tapjacking“). NFC Card Player zeigt keine überlagerten Fenster an und nutzt es nur, um sich nach vorn zu holen.",
            "For: autostart, display schedule, returning from Amazon Music/Spotify, app above the lock screen.\n\nWithout: these switches are locked; the app does not start by itself and does not come back after Amazon/Spotify.\n\n⚠ Security: apps could draw windows over other apps and intercept input (“tapjacking”). NFC Card Player shows no overlay windows and only uses it to come to the front."
        ))
        permRow(pBattery, { requestBatteryExemption() }, t(
            "Wofür: Schlaf-Timer, Stoptanz, gespeicherte Hörbuch-Stelle und Zeitschaltung im Hintergrund.\n\nOhne: Android kann die App im Hintergrund beenden – Timer laufen nicht ab, Stellen werden evtl. nicht gespeichert.\n\n⚠ Sicherheit: kein Datenrisiko, nur etwas mehr Akkuverbrauch.",
            "For: sleep timer, freeze dance, saved audiobook position and schedule in the background.\n\nWithout: Android may kill the app in the background – timers stop, positions may not be saved.\n\n⚠ Security: no data risk, only slightly more battery use."
        ))
        permRow(pAdmin, { requestAdmin() }, t(
            "Wofür: Display-Zeitschaltung – Display zur Aus-Zeit sofort aus.\n\nOhne: Das Display geht erst nach der normalen Bildschirm-Zeitsperre aus.\n\n⚠ Sicherheit: Geräteadministratoren können je nach Richtlinie das Gerät sperren, Passwörter ändern oder es zurücksetzen. NFC Card Player fordert NUR „Bildschirm sperren“ an. Solange aktiv, lässt sich die App nicht deinstallieren – vorher unter Sicherheit → Geräteadministratoren abschalten.",
            "For: display schedule – display off immediately at the off time.\n\nWithout: the display only turns off after the normal screen timeout.\n\n⚠ Security: device admins can, depending on the policy, lock the device, change passwords or wipe it. NFC Card Player ONLY requests “lock screen”. While active the app cannot be uninstalled – disable it under Security → Device admin apps first."
        ))
        permRow(pExact, {
            if (Build.VERSION.SDK_INT >= 31) startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }, t(
            "Wofür: Display-Zeitschaltung auf die Minute genau.\n\nOhne: Die Zeitschaltung kann einige Minuten zu spät schalten.\n\n⚠ Sicherheit: geringes Risiko – die App darf das Handy zu festen Zeiten aufwecken.",
            "For: display schedule to the minute.\n\nWithout: the schedule may switch a few minutes late.\n\n⚠ Security: low risk – the app may wake the phone at fixed times."
        ), hideWhenGranted = true)

        subHeader(t("Sperrbildschirm", "Lock screen"))
        actionRow(t("Displaysperre auf „Keine“ stellen", "Set screen lock to “None”"), t("Öffnen", "Open"), { openScreenLockSettings() }, t(
            "Android erlaubt Apps nicht, den Sperrbildschirm abzuschalten. Für eine Musikbox ohne Sperre: Displaysperre → „Keine“ (vorher PIN/Muster entfernen).\n\nℹ Das ist je nach Smartphone unterschiedlich: Menünamen und Ort weichen ab. Auf manchen Geräten (z. B. Xiaomi) muss zusätzlich „Sperrbildschirm überspringen“ aktiviert werden – zu finden in den Entwickleroptionen (Einstellungen → Über das Telefon → 7× auf die Build-/OS-Version tippen, dann Einstellungen → Zusätzliche Einstellungen → Entwickleroptionen).\n\n⚠ Sicherheit: Ohne Sperre hat jeder mit Zugriff auf das Handy vollen Zugriff auf ALLE Apps, Konten und Daten. Nur für ein eigenes Musikbox-Handy ohne private Konten empfehlenswert.",
            "Android does not allow apps to disable the lock screen. For a music box without a lock: screen lock → “None” (remove PIN/pattern first).\n\nℹ This differs between phones: menu names and locations vary. On some devices (e.g. Xiaomi) “Skip lock screen” must also be enabled – found in the developer options (Settings → About phone → tap the build/OS version 7×, then Settings → Additional settings → Developer options).\n\n⚠ Security: without a lock anyone with access to the phone has full access to ALL apps, accounts and data. Only recommended for a dedicated music box phone without private accounts."
        ))
        actionRow(t("Hersteller-Sperren (Autostart, Hintergrund)", "Manufacturer restrictions (autostart, background)"), t("App-Info", "App info"), {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }, t(
            "Viele Hersteller (z. B. Xiaomi, Samsung, Huawei/Honor, OnePlus, Oppo, Realme, Vivo) haben zusätzliche Sperren für Autostart, Hintergrundaktivität und „Pop-ups aus dem Hintergrund“. Klappt Autostart, Zeitschaltung oder die Rückkehr aus Amazon/Spotify nicht: in der App-Info unter Berechtigungen bzw. Akku alles für NFC Card Player erlauben.",
            "Many manufacturers (e.g. Xiaomi, Samsung, Huawei/Honor, OnePlus, Oppo, Realme, Vivo) add restrictions for autostart, background activity and “pop-ups from the background”. If autostart, the schedule or returning from Amazon/Spotify fail: allow everything for NFC Card Player in App info under permissions / battery."
        ))
        subHeader("Streaming (Amazon Music / Spotify)")
        button(t("Streaming-Karten verwalten", "Manage streaming cards")) {
            startActivity(Intent(this, AmazonActivity::class.java))
        }
        infoRow(t("So starten Streaming-Karten", "How streaming cards start"), t(
            "Die Karte öffnet die Playlist direkt in Amazon Music bzw. Spotify und startet sie. Läuft nach 5 Sekunden noch nichts, sendet die App einmal „Play“. Sobald Musik läuft, kommt NFC Card Player wieder nach vorne – dafür wird „Über anderen Apps einblenden“ benötigt. ⏮ ▶ ⏭ steuern Streaming über die normalen Medientasten.\n\nEs werden keine weiteren Berechtigungen benötigt.",
            "The card opens the playlist directly in Amazon Music or Spotify and starts it. If nothing plays after 5 seconds, the app presses “play” once. As soon as music plays, NFC Card Player comes back to the front – this needs “Display over other apps”. ⏮ ▶ ⏭ control streaming via the normal media keys.\n\nNo further permissions are needed."
        ))

        // ------------------------------------------------ 4. display schedule
        header(t("Display-Zeitschaltung", "Display schedule"), help = t(
            "Bis zu 3 Zeiträume mit eigenen Wochentagen. Im Zeitraum ist das Display aus (Karten werden nicht gelesen), danach schaltet die App es wieder ein und startet den Box-Modus. Wochentag = Tag, an dem der Zeitraum beginnt.\n\nBenötigt: „Über anderen Apps einblenden“ und „Display ausschalten (Geräteadministrator)“ – ohne diese Berechtigungen sind die Zeiträume gesperrt.\n\nHandy komplett ausschalten? Das dürfen Apps nicht (und ein ausgeschaltetes Handy kann keine App starten). Viele Handys haben das eingebaut, z. B. Xiaomi: Einstellungen → Akku bzw. Sicherheit → „Geplantes Ein-/Ausschalten“ (Ort je nach Version). Nach dem Hochfahren startet NFC Card Player per Autostart von selbst.",
            "Up to 3 periods with their own weekdays. During a period the display is off (cards are not read), afterwards the app turns it back on and starts box mode. Weekday = day the period starts.\n\nRequires “Display over other apps” and “Turn display off (device admin)” – without these permissions the periods are locked.\n\nSwitch the phone off completely? Apps are not allowed to do that (and a switched-off phone cannot start an app). Many phones have it built in, e.g. Xiaomi: Settings → Battery / Security → “Scheduled power on & off” (location depends on the version). After booting, NFC Card Player starts by itself via autostart."
        ))
        switchRow(t("Display aus = Pause", "Display off = pause"), "offPause", AppPrefs.offPause(this), emptyList(), default = false,
            help = t(
                "Wenn eingeschaltet, pausiert die Musik, sobald das Display ausgeschaltet wird – durch die Zeitschaltung oder mit dem An/Aus-Schalter. Weiter geht es mit ▶ oder durch erneutes Einstecken einer Karte.\n\nNicht betroffen: das automatische Ausschalten des Box-Modus nach 30 s – dort läuft die Musik weiter. Hinweis: Geht das Display außerhalb des Box-Modus durch die normale Bildschirm-Zeitsperre aus, pausiert die Musik ebenfalls (Android kann das nicht vom An/Aus-Schalter unterscheiden).",
                "When on, the music pauses as soon as the display is switched off – by the schedule or with the power button. Continue with ▶ or by inserting a card again.\n\nNot affected: the automatic display-off of box mode after 30 s – the music keeps playing there. Note: if the display goes off outside box mode through the normal screen timeout, the music pauses too (Android cannot tell that apart from the power button)."
            ))
        for (i in 0 until Period.COUNT) periodCard(i)

        // ------------------------------------------------ 5. box & volume (end of menu, default off)
        header(t("Box & Lautstärke", "Box & volume"))
        volumeRow()
        brightnessRow()
        switchRow(t("Display nach 30 s ohne Bedienung ganz ausschalten", "Switch the display off completely after 30 s without use"),
            "displayOff", AppPrefs.displayOff(this), listOf(pAdmin), default = false,
            help = t(
                "Im Box-Modus geht das Display nach 30 s ohne Bedienung komplett aus (die Musik läuft weiter). Spart Strom.\n\n⚠ Android liest NFC-Karten nur bei eingeschaltetem Display: Die Box muss dann vor jeder Bedienung und vor dem Einstecken einer Karte mit dem An/Aus-Schalter eingeschaltet werden.",
                "In box mode the display turns off completely after 30 s without use (music keeps playing). Saves power.\n\n⚠ Android only reads NFC cards while the display is on: the box then has to be switched on with the power button before every use and before inserting a card."
            ))
        box.addView(TextView(this).apply {
            textSize = 12f
            text = t("⚠ Dann vor jeder Bedienung und vor dem Einstecken einer Karte die Box mit dem An/Aus-Schalter einschalten.",
                "⚠ Then switch the box on with the power button before every use and before inserting a card.")
        })

        // ------------------------------------------------ 6. licenses
        header(t("Lizenzen", "Licenses"))
        button(t("Open-Source-Lizenzen anzeigen", "Show open-source licenses")) { showLicenses() }
        box.addView(TextView(this).apply {
            text = android.text.Html.fromHtml("© BamBam Design · <a href=\"https://www.bambam-design.com\">www.bambam-design.com</a>", android.text.Html.FROM_HTML_MODE_LEGACY)
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            textSize = 14f
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        })

        button(t("Zurück", "Back")) { finish() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) UiUtil.hideSystemBars(this)
    }

    override fun onResume() {
        super.onResume()
        refreshers.forEach { it() }
    }

    // ------------------------------------------------ rows

    private fun missingOf(req: List<Perm>) = req.filter { !it.granted() }

    /** Switch that is only usable when the required permissions are granted. */
    private fun switchRow(label: String, key: String, checked: Boolean, requires: List<Perm>, help: String? = null, default: Boolean = true,
                          blockedBy: (() -> String?)? = null) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        var setting = false
        val sw = MaterialSwitch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, c ->
                if (!setting) { AppPrefs.set(this@SettingsActivity, key, c); refreshers.forEach { it() } }
            }
        }
        val tvLabel = TextView(this).apply {
            text = label
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            setOnClickListener { if (sw.isEnabled) sw.toggle() }
        }
        // label – "?" – switch
        row.addView(tvLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (help != null) row.addView(helpButton(label, help))
        row.addView(sw)
        box.addView(row)
        val hint = TextView(this).apply { textSize = 12f; setTextColor(red) }
        box.addView(hint)
        refreshers += {
            val miss = missingOf(requires)
            val blocked = blockedBy?.invoke()
            val usable = miss.isEmpty() && blocked == null
            if (blocked != null) AppPrefs.set(this@SettingsActivity, key, false)  // blocked option is switched off
            sw.isEnabled = usable
            tvLabel.alpha = if (usable) 1f else 0.5f
            // without permission (or blocked by another option): shown off and locked; otherwise the saved value
            setting = true
            sw.isChecked = usable && AppPrefs.prefs(this@SettingsActivity).getBoolean(key, default)
            setting = false
            hint.visibility = if (usable) View.GONE else View.VISIBLE
            hint.text = if (miss.isNotEmpty()) t("Gesperrt – benötigt: ", "Locked – requires: ") + miss.joinToString(", ") { it.name }
                else blocked ?: ""
        }
    }

    /** Permission row: large ✓/✗, name, "?" with details, action button. */
    private fun permRow(p: Perm, action: () -> Unit, help: String, hideWhenGranted: Boolean = false) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
        }
        val mark = TextView(this).apply {
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        val size = (34 * dp).toInt()
        row.addView(mark, LinearLayout.LayoutParams(size, size).apply { marginEnd = (10 * dp).toInt() })
        val title = TextView(this).apply {
            text = p.name
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        }
        row.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(helpButton(p.name, help))
        val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setOnClickListener { action() }
        }
        row.addView(btn)
        box.addView(row)
        refreshers += {
            val ok = p.granted()
            mark.text = if (ok) "✓" else "✗"
            mark.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (ok) green else red)
            }
            btn.text = if (ok) t("Ändern", "Change") else t("Erlauben", "Allow")
            // permissions that Android usually grants by itself are only listed while missing
            row.visibility = if (ok && hideWhenGranted) View.GONE else View.VISIBLE
        }
    }

    /** Row with a text, "?" and an action button. */
    private fun actionRow(label: String, buttonText: String, action: () -> Unit, help: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply {
            text = label
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(helpButton(label, help))
        row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = buttonText
            setOnClickListener { action() }
        })
        box.addView(row)
    }

    /** Maximum volume slider (works without extra permission). */
    private fun volumeRow() {
        val label = TextView(this).apply { setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium) }
        fun txt(v: Int) = if (v >= 100) t("Maximale Lautstärke: keine Begrenzung", "Maximum volume: no limit")
            else t("Maximale Lautstärke: $v %", "Maximum volume: $v %")
        val slider = com.google.android.material.slider.Slider(this).apply {
            valueFrom = 10f; valueTo = 100f; stepSize = 5f
            value = AppPrefs.volumeLimit(this@SettingsActivity).coerceIn(10, 100).toFloat()
            label.text = txt(value.toInt())
            addOnChangeListener { _, v, _ ->
                AppPrefs.set(this@SettingsActivity, "volumeLimit", v.toInt())
                label.text = txt(v.toInt())
                VolumeLimit.apply(this@SettingsActivity)
            }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(helpButton(t("Maximale Lautstärke", "Maximum volume"), t(
            "Begrenzt die Medien-Lautstärke – auch am Bluetooth-Lautsprecher. Wird lauter gedreht, setzt die App die Lautstärke sofort auf die Grenze zurück. Keine zusätzliche Berechtigung nötig.",
            "Limits the media volume – also on the Bluetooth speaker. If it is turned up further, the app sets it back to the limit right away. No extra permission needed."
        )))
        box.addView(row)
        box.addView(slider)
    }

    /** Box mode brightness after tapping. */
    private fun brightnessRow() {
        val label = TextView(this).apply { setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium) }
        fun txt(v: Int) = t("Helligkeit im Box-Modus nach Antippen: $v %", "Box mode brightness after tapping: $v %")
        val slider = com.google.android.material.slider.Slider(this).apply {
            valueFrom = 5f; valueTo = 100f; stepSize = 5f
            value = (AppPrefs.boxBrightness(this@SettingsActivity).coerceIn(5, 100) / 5 * 5).toFloat()
            label.text = txt(value.toInt())
            addOnChangeListener { _, v, _ ->
                AppPrefs.set(this@SettingsActivity, "boxBrightness", v.toInt())
                label.text = txt(v.toInt())
            }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(helpButton(t("Helligkeit im Box-Modus", "Box mode brightness"), t(
            "So hell wird das Display, wenn du im Box-Modus tippst (auch auf die Player-Leiste). Nach 15 s ohne Bedienung wird es wieder dunkel.",
            "How bright the display gets when you tap in box mode (also on the player bar). After 15 s without use it goes dark again."
        )))
        box.addView(row)
        box.addView(slider)
    }

    /** Text with "?" only. */
    private fun infoRow(label: String, help: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply {
            text = label
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(helpButton(label, help))
        box.addView(row)
    }

    private fun helpButton(title: String, text: String) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = "?"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            minWidth = 0; minimumWidth = 0
            val s = (40 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply { marginStart = (4 * dp).toInt(); marginEnd = (4 * dp).toInt() }
            insetTop = 0; insetBottom = 0
            setPadding(0, 0, 0, 0)
            cornerRadius = s / 2
            setOnClickListener {
                MaterialAlertDialogBuilder(this@SettingsActivity)
                    .setTitle(title)
                    .setMessage(text)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }

    // ------------------------------------------------ display periods

    private fun periodCard(i: Int) {
        val pad = (12 * dp).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 12 * dp
                setStroke((1 * dp).toInt(), Color.argb(0x55, 0x80, 0x80, 0x80))
            }
        }
        box.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (8 * dp).toInt()
        })
        var p = Period.load(this, i)
        fun store(n: Period) { p = n; n.save(this); Schedule.reschedule(this) }

        val sw = MaterialSwitch(this).apply {
            text = t("Zeitraum ${i + 1}", "Period ${i + 1}")
            setTypeface(typeface, Typeface.BOLD)
            isChecked = p.enabled
        }
        card.addView(sw)
        val lockHint = TextView(this).apply { textSize = 12f; setTextColor(red) }
        card.addView(lockHint)

        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bOff = Button(this)
        val bOn = Button(this)
        times.addView(bOff, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(bOn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (8 * dp).toInt() })
        card.addView(times)

        val names = if (I18n.en) listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su") else listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
        val days = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val accent = com.google.android.material.color.MaterialColors.getColor(card, androidx.appcompat.R.attr.colorPrimary)
        val onAccent = com.google.android.material.color.MaterialColors.getColor(card, com.google.android.material.R.attr.colorOnPrimary)
        val normalText = TextView(this).currentTextColor
        fun styleDay(v: TextView, on: Boolean) {
            v.background = GradientDrawable().apply {
                cornerRadius = 8 * dp
                if (on) setColor(accent) else setStroke((1 * dp).toInt(), Color.argb(0x88, 0x80, 0x80, 0x80))
            }
            v.setTextColor(if (on) onAccent else normalText)
            v.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
        val toggles = names.mapIndexed { d, n ->
            TextView(this).apply {
                text = n
                textSize = 13f
                gravity = Gravity.CENTER
                styleDay(this, p.hasDay(d))
                setOnClickListener {
                    if (!isEnabled) return@setOnClickListener
                    val on = !p.hasDay(d)
                    store(p.copy(days = if (on) p.days or (1 shl d) else p.days and (1 shl d).inv()))
                    styleDay(this, on)
                    refreshers.forEach { it() }
                }
            }.also {
                days.addView(it, LinearLayout.LayoutParams(0, (36 * dp).toInt(), 1f).apply {
                    marginStart = (2 * dp).toInt(); marginEnd = (2 * dp).toInt()
                })
            }
        }
        card.addView(days)
        val summary = TextView(this).apply { textSize = 12f }
        card.addView(summary)

        fun refresh() {
            val miss = missingOf(listOf(pOverlay, pAdmin))
            val allowed = miss.isEmpty()
            sw.isEnabled = allowed
            sw.alpha = if (allowed) 1f else 0.5f
            lockHint.visibility = if (allowed) View.GONE else View.VISIBLE
            lockHint.text = t("Gesperrt – benötigt: ", "Locked – requires: ") + miss.joinToString(", ") { it.name } +
                t(" (unter „Berechtigungen“ erteilen)", " (grant under “Permissions”)")
            bOff.text = t("Aus ", "Off ") + AppPrefs.fmt(p.off)
            bOn.text = t("An ", "On ") + AppPrefs.fmt(p.on)
            val active = allowed && p.enabled
            listOf(bOff, bOn).forEach { it.isEnabled = active }
            toggles.forEachIndexed { d, v -> v.isEnabled = active; v.alpha = if (active) 1f else 0.4f; styleDay(v, p.hasDay(d)) }
            summary.text = when {
                !p.enabled -> t("ausgeschaltet", "switched off")
                p.days == 0 -> t("⚠ kein Wochentag gewählt – wirkungslos", "⚠ no weekday selected – no effect")
                else -> t("Display aus ${AppPrefs.fmt(p.off)} – an ${AppPrefs.fmt(p.on)}" + if (p.off > p.on) " (über Nacht)" else "",
                    "Display off ${AppPrefs.fmt(p.off)} – on ${AppPrefs.fmt(p.on)}" + if (p.off > p.on) " (overnight)" else "")
            }
        }
        sw.setOnCheckedChangeListener { _, c -> store(p.copy(enabled = c)); refresh() }
        bOff.setOnClickListener {
            TimePickerDialog(this, { _, h, m -> store(p.copy(off = h * 60 + m)); refresh() }, p.off / 60, p.off % 60, true).show()
        }
        bOn.setOnClickListener {
            TimePickerDialog(this, { _, h, m -> store(p.copy(on = h * 60 + m)); refresh() }, p.on / 60, p.on % 60, true).show()
        }
        refreshers += { refresh() }
    }

    // ------------------------------------------------ actions

    /** Opens the lock screen / security menu (fallback: settings). */
    private fun openScreenLockSettings() {
        // the lock screen / security menu, where "Screen lock → None" can be chosen
        // (ACTION_SET_NEW_PASSWORD would start setting a NEW lock instead)
        val tries = listOf(
            Intent(Settings.ACTION_SECURITY_SETTINGS),   // Xiaomi/HyperOS: "Fingerprints, face data and screen lock"
            Intent("android.settings.LOCK_SCREEN_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in tries) {
            try { startActivity(i); return } catch (_: Exception) {}
        }
    }

    private fun hasStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (e: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun requestAdmin() {
        if (Schedule.isAdmin(this)) {
            Toast.makeText(this, t("Ist bereits erlaubt. Abschalten unter Sicherheit → Geräteadministratoren.", "Already allowed. Disable under Security → Device admin apps."), Toast.LENGTH_LONG).show()
            return
        }
        startActivity(
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Schedule.adminComponent(this))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, t(
                    "Wird nur benötigt, um das Display zur eingestellten Zeit auszuschalten.",
                    "Only needed to turn the display off at the scheduled time."
                ))
        )
    }

    private fun showLicenses() {
        val text = try {
            resources.openRawResource(R.raw.notices).bufferedReader().readText() +
                resources.openRawResource(R.raw.license_apache2).bufferedReader().readText()
        } catch (e: Exception) { "" }
        val pad = (16 * dp).toInt()
        val tv = TextView(this).apply {
            this.text = text
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(pad, pad, pad, pad)
            setTextIsSelectable(true)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(t("Open-Source-Lizenzen", "Open-source licenses"))
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("OK", null)
            .show()
    }

    // ------------------------------------------------ helpers

    private fun header(s: String, help: String? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (24 * dp).toInt(), 0, (4 * dp).toInt())
        }
        row.addView(TextView(this).apply {
            text = s
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (help != null) row.addView(helpButton(s, help))
        box.addView(row)
    }

    private fun subHeader(s: String) = TextView(this).apply {
        text = s
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
        setPadding(0, (16 * dp).toInt(), 0, (2 * dp).toInt())
        box.addView(this)
    }

    private fun button(s: String, onClick: (View) -> Unit) = Button(this).apply {
        text = s
        setOnClickListener(onClick)
        box.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
}
