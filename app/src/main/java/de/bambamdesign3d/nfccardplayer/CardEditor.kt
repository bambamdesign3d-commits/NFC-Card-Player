package de.bambamdesign3d.nfccardplayer

import android.os.Environment
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** Dialog to create / edit a card. Everything is stored inside the app. */
class CardEditor(
    private val a: AppCompatActivity,
    private val onSaved: () -> Unit,
    private val onWrite: (Int) -> Unit
) {
    companion object {
        private val FOLDER_MODES = setOf(MODE_ROW, MODE_RANDOM, MODE_SINGLE, MODE_SINGLE_RANDOM)
        private val STATESAVE_MODES = setOf(MODE_ROW, MODE_RANDOM, MODE_SINGLE)
        val musicDir: File get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
    }

    private val dp = a.resources.displayMetrics.density

    /** card = existing card to edit; draft = prefilled values for a new card (e.g. a shared streaming link). */
    fun show(card: Card?, draft: Card? = null) {
        val pad = (16 * dp).toInt()
        val c = card ?: draft ?: Card(CardStore.nextFree(a))

        val etNumber = edit(t("Nummer 1–999", "Number 1–999"), c.number.toString(), InputType.TYPE_CLASS_NUMBER)
        val etName = edit(t("Name (wird angezeigt)", "Name (shown)"), c.name ?: "")
        val spSource = spinner(
            listOf(t("Ordner auf dem Handy", "Folder on the phone"), "Amazon Music", "Spotify"),
            when { c.isAmazon -> 1; c.isSpotify -> 2; else -> 0 }
        )
        val spMode = spinner(MODES.map { Card.modeLabel(it) }, MODES.indexOf(c.mode).coerceAtLeast(0))
        val etFolder = edit("folder", c.folder ?: "", InputType.TYPE_TEXT_VARIATION_URI)
        val btnFolder = Button(a).apply { text = t("Ordner auswählen", "Choose folder") }
        val etFile = edit("file", c.file ?: "")
        val btnFile = Button(a).apply { text = t("Titel auswählen", "Choose track") }
        val cbState = CheckBox(a).apply {
            text = t("statesave – letzte Stelle merken (startet 10 s vorher)", "statesave – remember position (resumes 10 s earlier)")
            isChecked = c.statesave
        }
        val btnReset = Button(a).apply { text = t("Gespeicherte Stelle löschen", "Clear saved position") }
        val etLink = edit(t("Link (Teilen-Link aus Amazon Music / Spotify)", "Link (share link from Amazon Music / Spotify)"), c.link ?: "", InputType.TYPE_TEXT_VARIATION_URI)
        val etMinutes = number(c.minutes)
        val etFade = number(c.fade)
        val etPlayMin = number(c.playMin)
        val etPlayMax = number(c.playMax)
        val etPauseMin = number(c.pauseMin)
        val etPauseMax = number(c.pauseMax)
        // bottom bar: Cancel – Write card – Save (all outlined); Delete inside the form
        fun outlined(txt: String) = com.google.android.material.button.MaterialButton(
            a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = txt; isAllCaps = false; maxLines = 1
            minWidth = 0; minimumWidth = 0
            setPadding((12 * dp).toInt(), paddingTop, (12 * dp).toInt(), paddingBottom)
        }
        val btnCancel = outlined(t("Abbrechen", "Cancel"))
        val btnWrite = outlined(t("Karte schreiben", "Write card"))
        val btnSave = outlined(t("Speichern", "Save"))
        val btnDelete = outlined(t("Karte löschen", "Delete card")).apply {
            setTextColor(android.graphics.Color.rgb(0xC6, 0x28, 0x28))
            strokeColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(0xC6, 0x28, 0x28))
            visibility = if (card != null) View.VISIBLE else View.GONE
        }

        val gMode = group(label(t("Abspielart (mode)", "Mode")), spMode)
        val gFolder = group(label(t("Ordner (folder)", "Folder")), etFolder, btnFolder)
        val gFile = group(label(t("Datei (file)", "File")), etFile, btnFile)
        val gState = group(cbState, btnReset)
        var imageUrl: String? = null
        val ivPreview = android.widget.ImageView(a).apply {
            visibility = View.GONE
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams((96 * dp).toInt(), (96 * dp).toInt())
        }
        CoverStore.get(a, c.number)?.takeIf { card != null && c.isStream }?.let { ivPreview.setImageBitmap(it); ivPreview.visibility = View.VISIBLE }
        val gLink = group(label("Link"), etLink, ivPreview)
        val gTimer = group(
            label(t("Minuten bis Pause", "Minutes until pause")), etMinutes,
            label(t("Ausblenden am Ende (Sekunden)", "Fade out at the end (seconds)")), etFade
        )
        val gFreeze = group(
            label(t("Musik läuft zufällig (Sekunden) von – bis", "Music plays randomly (seconds) from – to")),
            row(etPlayMin, etPlayMax),
            label(t("Pause zufällig (Sekunden) von – bis", "Pause randomly (seconds) from – to")),
            row(etPauseMin, etPauseMax)
        )

        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(label(t("Kartennummer", "Card number"))); addView(etNumber)
            addView(label("Name")); addView(etName)
            addView(label(t("Quelle", "Source"))); addView(spSource)
            listOf(gMode, gFolder, gFile, gState, gLink, gTimer, gFreeze).forEach { addView(it) }
            addView(btnDelete, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (12 * dp).toInt()
            })
        }
        val bar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setPadding(pad, pad / 2, pad, pad / 2)
            val gap = (8 * dp).toInt()
            listOf(btnCancel, btnWrite, btnSave).forEachIndexed { i, b ->
                addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) marginStart = gap
                })
            }
        }
        // the form scrolls, the button bar stays visible below it
        val content = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(ScrollView(a).apply { addView(box) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bar)
        }

        fun amazon() = spSource.selectedItemPosition != 0
        fun streamSource() = if (spSource.selectedItemPosition == 2) SOURCE_SPOTIFY else SOURCE_AMAZON
        fun mode() = MODES[spMode.selectedItemPosition]
        fun refresh() {
            val am = amazon()
            val m = mode()
            gMode.visibility = vis(!am)
            gFolder.visibility = vis(!am && m in FOLDER_MODES)
            gFile.visibility = vis(!am && m == MODE_SINGLE)
            gState.visibility = vis(!am && m in STATESAVE_MODES)
            btnReset.visibility = vis(card != null && StateStore.load(a, card.number) != null)
            gLink.visibility = vis(am)
            gTimer.visibility = vis(!am && m == MODE_TIMER)
            gFreeze.visibility = vis(!am && m == MODE_FREEZEDANCE)
        }
        // streaming link: fetch name and preview picture directly from Amazon Music / Spotify
        var fetchedFor: String? = null
        fun autoName() {
            val l = AmazonStore.extractLink(etLink.text.toString()) ?: return
            if (!l.startsWith("http") || l == fetchedFor) return
            fetchedFor = l
            val needName = etName.text.isBlank()
            if (needName) etName.hint = t("Name wird gesucht …", "Looking up name …")
            Thread {
                val info = try { NameFetcher.fetchInfo(l) } catch (e: Exception) { NameFetcher.Info(null, null) }
                val bmp = info.imageUrl?.let { CoverStore.loadUrl(it) }
                a.runOnUiThread {
                    if (fetchedFor != l) return@runOnUiThread
                    etName.hint = t("Name (wird angezeigt)", "Name (shown)")
                    if (info.name != null && etName.text.isBlank()) etName.setText(info.name)
                    imageUrl = info.imageUrl
                    if (bmp != null) { ivPreview.setImageBitmap(bmp); ivPreview.visibility = View.VISIBLE }
                }
            }.start()
        }
        etLink.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, b: Int, n: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, n: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { autoName() }
        })
        autoName()
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refresh()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spSource.onItemSelectedListener = listener
        spMode.onItemSelectedListener = listener
        refresh()

        btnFolder.setOnClickListener {
            val dirs = musicDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
            if (dirs.isEmpty()) {
                toast(t("Keine Ordner in ${musicDir.absolutePath} (Speicherzugriff erlaubt?)", "No folders in ${musicDir.absolutePath} (storage access granted?)"))
                return@setOnClickListener
            }
            MaterialAlertDialogBuilder(a)
                .setTitle("Music/")
                .setItems(dirs.map { it.name }.toTypedArray()) { _, i ->
                    etFolder.setText(dirs[i].absolutePath)
                    if (etName.text.isBlank()) etName.setText(dirs[i].name)
                }
                .show()
        }
        btnFile.setOnClickListener {
            val files = Card(0, folder = etFolder.text.toString().trim()).files()
            if (files.isEmpty()) {
                toast(t("Erst einen Ordner mit Audiodateien wählen", "Choose a folder with audio files first"))
                return@setOnClickListener
            }
            MaterialAlertDialogBuilder(a)
                .setTitle(t("Titel", "Track"))
                .setItems(files.map { it.name }.toTypedArray()) { _, i -> etFile.setText(files[i].name) }
                .show()
        }
        btnReset.setOnClickListener {
            card?.let {
                StateStore.clear(a, it.number)
                toast(t("Gespeicherte Stelle gelöscht – startet wieder von vorn", "Saved position cleared – starts from the beginning"))
                btnReset.visibility = View.GONE
            }
        }

        lateinit var dialog: AlertDialog

        fun int(e: EditText, def: Int) = e.text.toString().trim().toIntOrNull() ?: def

        fun save(): Int? {
            val nr = etNumber.text.toString().trim().toIntOrNull()
            if (nr == null || nr !in 1..999) { toast(t("Bitte eine Nummer von 1 bis 999 eingeben", "Please enter a number from 1 to 999")); return null }
            val am = amazon()
            val m = mode()
            val folder = etFolder.text.toString().trim().ifBlank { null }
            val file = etFile.text.toString().trim().ifBlank { null }
            val link = AmazonStore.extractLink(etLink.text.toString()) ?: etLink.text.toString().trim().ifBlank { null }
            when {
                am && (link == null || !link.startsWith("http")) -> { toast(t("Bitte einen gültigen Link einfügen", "Please paste a valid link")); return null }
                !am && m in FOLDER_MODES && folder == null -> { toast(t("Bitte einen Ordner wählen", "Please choose a folder")); return null }
                !am && m == MODE_SINGLE && file == null -> { toast(t("Bitte einen Titel wählen", "Please choose a track")); return null }
            }
            val other = CardStore.get(a, nr)
            if (other != null && nr != card?.number) toast(t("Karte $nr (${other.title()}) wurde ersetzt", "Card $nr (${other.title()}) was replaced"))
            val new = Card(
                number = nr,
                name = etName.text.toString().trim().ifBlank { null },
                source = if (am) streamSource() else SOURCE_LOCAL,
                mode = if (am) MODE_ROW else m,
                folder = if (!am && m in FOLDER_MODES) folder else null,
                file = if (!am && m == MODE_SINGLE) file else null,
                link = if (am) link else null,
                statesave = !am && m in STATESAVE_MODES && cbState.isChecked,
                minutes = int(etMinutes, 30).coerceAtLeast(1),
                fade = int(etFade, 30).coerceAtLeast(0),
                playMin = int(etPlayMin, 7).coerceAtLeast(1),
                playMax = int(etPlayMax, 30).coerceAtLeast(1),
                pauseMin = int(etPauseMin, 3).coerceAtLeast(1),
                pauseMax = int(etPauseMax, 8).coerceAtLeast(1)
            )
            CardStore.put(a, new, card?.number)
            if (card != null && card.number != nr) CoverStore.move(a, card.number, nr)
            if (new.usesFolder && (card?.folder != new.folder || card?.file != new.file)) CoverStore.resetTried(a, nr)
            // streaming: preview picture from Amazon Music / Spotify (new card or changed link)
            if (am && (!CoverStore.has(a, nr) || card?.link != new.link)) {
                val url = imageUrl
                Thread {
                    val u = url ?: NameFetcher.fetchInfo(link!!).imageUrl
                    if (u != null && CoverStore.download(a, nr, u)) a.runOnUiThread { onSaved() }
                }.start()
            }
            onSaved()
            return nr
        }

        btnWrite.setOnClickListener { save()?.let { nr -> dialog.dismiss(); onWrite(nr) } }

        dialog = MaterialAlertDialogBuilder(a)
            .setTitle(if (card == null) t("Neue Karte", "New card") else t("Karte ${card.number} bearbeiten", "Edit card ${card.number}"))
            .setView(content)
            .create()
        dialog.setOnShowListener {
            // a bit wider, so the three buttons fit in one line
            val dm = a.resources.displayMetrics
            dialog.window?.setLayout(minOf((dm.widthPixels * 0.94f).toInt(), (640 * dm.density).toInt()), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener { if (save() != null) dialog.dismiss() }
        run {
            btnDelete.setOnClickListener {
                MaterialAlertDialogBuilder(a)
                    .setTitle(t("Karte ${card!!.number} löschen?", "Delete card ${card!!.number}?"))
                    .setMessage(card.title())
                    .setPositiveButton(t("Löschen", "Delete")) { _, _ ->
                        CardStore.remove(a, card.number)
                        onSaved()
                        dialog.dismiss()
                    }
                    .setNegativeButton(t("Abbrechen", "Cancel"), null)
                    .show()
            }
        }
        dialog.show()
    }

    // --- helpers

    private fun vis(b: Boolean) = if (b) View.VISIBLE else View.GONE

    private fun edit(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(a).apply {
        this.hint = hint
        inputType = type
        setText(value)
    }

    private fun number(v: Int) = edit("", v.toString(), InputType.TYPE_CLASS_NUMBER)

    private fun spinner(items: List<String>, sel: Int) = Spinner(a).apply {
        adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(sel)
    }

    private fun label(s: String) = TextView(a).apply {
        text = s
        setPadding(0, (8 * dp).toInt(), 0, 0)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
    }

    private fun group(vararg views: View) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        views.forEach { addView(it) }
    }

    private fun row(l: View, r: View) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(l, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(a).apply { text = "  –  " })
        addView(r, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun toast(s: String) = Toast.makeText(a, s, Toast.LENGTH_LONG).show()
}
