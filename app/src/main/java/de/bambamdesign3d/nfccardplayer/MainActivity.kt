package de.bambamdesign3d.nfccardplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.content.res.ColorStateList
import com.google.android.material.button.MaterialButton
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_WRITE = "write_nr"
        const val EXTRA_WAKE = "wake"

        @Volatile
        var current: MainActivity? = null

        @Volatile
        var isFront = false
    }

    // ---------------------------------------------------------------- state

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private var cards: Map<Int, Card> = emptyMap()
    private val fileCount = mutableMapOf<Int, Int>()
    private var currentCard: Card? = null
    private var pendingCard: Int? = null
    private var pendingWrite: Int? = null
    private var writeDialog: AlertDialog? = null
    private var lastScanNr: Int? = null
    private var lastScanTime = 0L
    private var languageEn = false
    private var coverNr: Int? = -1
    private var pickCoverFor: Int? = null
    private var playerPrefs = PlayerPrefs()
    private var savedDecorBg: android.graphics.drawable.Drawable? = null
    private var boxOverlay: View? = null
    private var lastBoxTap = 0L
    private var lastOffState = false

    private val handler = Handler(Looper.getMainLooper())
    private var nfc: NfcAdapter? = null

    // ---------------------------------------------------------------- views

    private lateinit var rootLayout: FrameLayout
    private lateinit var contentLayout: View
    private lateinit var playerCard: View
    private lateinit var topButtons: View
    private lateinit var tvCard: TextView
    private lateinit var tvTrack: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvTop: TextView
    private lateinit var tvLine2: TextView
    private lateinit var battery: BatteryView

    /** Phone battery level for the small display. */
    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = updateBattery(i)
    }

    private fun updateBattery(i: Intent?) {
        i ?: return
        val lvl = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
        if (lvl >= 0 && scale > 0) battery.level = lvl * 100 / scale
        val st = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        battery.charging = st == android.os.BatteryManager.BATTERY_STATUS_CHARGING || st == android.os.BatteryManager.BATTERY_STATUS_FULL
    }

    /** Apply the selected colour scheme to the small player bar. */
    private fun applyCardTheme() {
        val th = CardTheme.get(playerPrefs.farbe)
        (playerCard as com.google.android.material.card.MaterialCardView).setCardBackgroundColor(th.background)
        tvTop.setTextColor(th.text)
        tvTitle.setTextColor(th.subText)
        tvLine2.setTextColor(th.accent)
        listOf(btnPrev, btnNext).forEach { (it as MaterialButton).iconTint = ColorStateList.valueOf(th.accent) }
        (btnPlay as MaterialButton).apply {
            backgroundTintList = ColorStateList.valueOf(th.accent)   // e.g. red box …
            iconTint = ColorStateList.valueOf(th.background)        // … with black symbol
        }
        pbMini.progressTintList = android.content.res.ColorStateList.valueOf(th.accent)
        pbMini.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(th.track)
        battery.frameColor = th.text
        battery.barColor = th.accent
    }
    private lateinit var tvTitle: MarqueeTextView
    private lateinit var ivCover: ImageView
    private lateinit var pbMini: ProgressBar
    private lateinit var btnPlay: Button
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var btnBox: Button
    private lateinit var btnRotate: Button
    private lateinit var btnReload: Button
    private lateinit var btnSettings: Button
    private lateinit var btnAdd: Button
    private lateinit var btnPermission: Button
    private lateinit var adapter: CardAdapter
    private lateinit var editor: CardEditor
    private lateinit var imageSearch: ImageSearch
    private var lastInteraction = SystemClock.elapsedRealtime()
    /** time the app itself switched the display off (box display-off / schedule) */
    private var appTurnedOff = 0L
    /** first start after installation: box mode stays off until the next app start */
    private var firstSession = false

    /** After 30 s without use the app switches to box mode by itself; awake box mode gets dark after 15 s. */
    private val idleCheck = object : Runnable {
        override fun run() {
            val idleMs = SystemClock.elapsedRealtime() - lastInteraction
            if (boxOverlay == null) {
                if (!firstSession && AppPrefs.autoBox(this@MainActivity) && isFront && hasWindowFocus() && pendingWrite == null && idleMs > 30_000) enterBox()
            } else if (boxAwake) {
                if (idleMs > 15_000) dimBox()   // awake box mode: dark again after 15 s
            } else {
                applyDarkBrightness() // the system may raise the brightness on touch – set it again
                // option: display completely off after 30 s without use
                if (AppPrefs.displayOff(this@MainActivity) && idleMs > 30_000 && pendingWrite == null && isInteractive()) {
                    turnDisplayOff()
                }
            }
            // during an off period the display may go off: do not keep it on
            if (Schedule.isOffTime(this@MainActivity)) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            handler.postDelayed(this, 5_000)
        }
    }

    private var boxAwake = false
    private val boxButtonState = mutableMapOf<MaterialButton, Triple<ColorStateList?, ColorStateList?, Pair<ColorStateList?, Int>>>()

    private fun setPlayIcon(playing: Boolean) {
        (btnPlay as MaterialButton).setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    /** Box mode: all top buttons in the same style – dark red border and text, black inside. */
    private fun styleBoxButtons(box: Boolean) {
        val darkRed = ColorStateList.valueOf(Color.rgb(0x9A, 0x10, 0x10))
        val buttons = listOf(btnSettings, btnBox, btnRotate, btnReload, btnAdd).map { it as MaterialButton }
        for (b in buttons) {
            if (box) {
                if (b !in boxButtonState) boxButtonState[b] = Triple(b.backgroundTintList, b.textColors, b.strokeColor to b.strokeWidth)
                b.backgroundTintList = ColorStateList.valueOf(Color.BLACK)
                b.setTextColor(darkRed)
                b.strokeColor = darkRed
                b.strokeWidth = (2 * resources.displayMetrics.density).toInt()
            } else {
                val st = boxButtonState.remove(b) ?: continue
                b.backgroundTintList = st.first
                st.second?.let { b.setTextColor(it) }
                b.strokeColor = st.third.first
                b.strokeWidth = st.third.second
            }
        }
    }
    private var boxHint: TextView? = null
    private var brightnessToggle = false

    /** Minimum brightness; value alternates slightly so Android really applies it again. */
    private fun applyDarkBrightness() {
        brightnessToggle = !brightnessToggle
        window.attributes = window.attributes.apply { screenBrightness = if (brightnessToggle) 0.01f else 0.011f }
    }

    /** Box mode, awake stage: normal brightness, bar readable, second tap exits. */
    private fun wakeBox() {
        boxAwake = true
        window.attributes = window.attributes.apply { screenBrightness = AppPrefs.boxBrightness(this@MainActivity).coerceIn(5, 100) / 100f }
        playerCard.alpha = 1f
        topButtons.alpha = 1f
        boxHint?.apply {
            text = t("Nochmal tippen zum Beenden (nicht auf der Player-Leiste)\nnach 15 s ohne Bedienung wieder dunkel", "Tap again to exit (not on the player bar)\ndark again after 15 s without use")
            setTextColor(Color.rgb(150, 150, 150))
            visibility = View.VISIBLE
        }
        handler.removeCallbacks(ticker); handler.post(ticker)
    }

    /** Box mode, dark stage. */
    private fun dimBox() {
        boxAwake = false
        applyDarkBrightness()
        playerCard.alpha = 0.5f
        topButtons.alpha = 0.6f
        boxHint?.visibility = View.INVISIBLE
    }

    // ---------------------------------------------------------------- activity results

    private val importJson = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = try {
            contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) { null }
        if (text == null) { toast(t("Datei konnte nicht gelesen werden", "Could not read the file")); return@registerForActivityResult }
        MaterialAlertDialogBuilder(this)
            .setTitle(t("Karten importieren", "Import cards"))
            .setMessage(t(
                "Sollen die Karten aus der Datei die vorhandenen ergänzen (gleiche Nummern werden überschrieben) oder alle ersetzen?",
                "Should the cards from the file be added (same numbers are overwritten) or replace all cards?"
            ))
            .setPositiveButton(t("Ergänzen", "Add")) { _, _ -> doImport(text, false) }
            .setNeutralButton(t("Alle ersetzen", "Replace all")) { _, _ -> doImport(text, true) }
            .setNegativeButton(t("Abbrechen", "Cancel"), null)
            .show()
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val nr = pickCoverFor ?: return@registerForActivityResult
        pickCoverFor = null
        if (uri != null && CoverStore.setFromUri(this, nr, uri)) {
            coverNr = -1
            adapter.notifyDataSetChanged()
            toast(t("Bild für Karte $nr gespeichert", "Picture saved for card $nr"))
        }
    }

    // ---------------------------------------------------------------- receivers / timers

    /** Display turns on -> bring the app above the lock screen. */
    private val screenOnReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val now = SystemClock.elapsedRealtime()
            if (i.action == Intent.ACTION_SCREEN_OFF) {
                // "display off = pause": power button (or timeout during an off period) pauses the music.
                // Not when the app switched the display off itself (box option; schedule pauses in goDark).
                if (AppPrefs.offPause(c) && now - appTurnedOff > 3000) {
                    val audio = c.getSystemService(android.media.AudioManager::class.java)
                    if (audio.isMusicActive) StreamStarter.key(c, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
                }
                return
            }
            lastInteraction = now
            if (!AppPrefs.overLock(c) || Schedule.isOffTime(c)) return
            try {
                startActivity(
                    Intent(c, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        .putExtra(EXTRA_WAKE, true)
                )
            } catch (_: Exception) {
            }
        }
    }

    /** Checks every minute whether the off time has started (in addition to the alarm). */
    private val nightCheck = object : Runnable {
        override fun run() {
            val off = Schedule.isOffTime(this@MainActivity)
            if (off && !lastOffState && boxOverlay != null) goDark(AppPrefs.offPause(this@MainActivity))
            lastOffState = off
            handler.postDelayed(this, 60_000)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateUi()
            handler.postDelayed(this, if (boxOverlay != null) 1000 else 500)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(this)
        languageEn = I18n.en
        setContentView(R.layout.activity_main)
        nfc = NfcAdapter.getDefaultAdapter(this)
        bindViews()
        applyTexts()
        UiUtil.hideSystemBars(this)
        applyOrientation()

        playerPrefs = PlayerPrefs.load(this)
        applyCardTheme()
        updateBattery(ContextCompat.registerReceiver(
            this, batteryReceiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        ))
        rootLayout.post { applyPlayerLayout(); topButtons.post { applyPlayerLayout() } }
        topButtons.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateContentPadding() }

        btnPlay.setOnClickListener {
            if (currentCard?.isStream == true) { streamControl(0); return@setOnClickListener }
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else {
                if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0)
                c.play()
            }
        }
        btnPrev.setOnClickListener {
            if (currentCard?.isStream == true) { streamControl(-1); return@setOnClickListener }
            val c = controller ?: return@setOnClickListener
            if (c.currentPosition > 5000 || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
        }
        btnNext.setOnClickListener {
            if (currentCard?.isStream == true) { streamControl(1); return@setOnClickListener }
            controller?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem()
        }
        btnBox.setOnClickListener { if (boxOverlay == null) enterBox() else exitBox() }
        btnReload.setOnClickListener { CardStore.invalidate(); loadCards(showToast = true) }
        btnSettings.setOnClickListener { showSettingsMenu(it) }
        btnPermission.setOnClickListener { requestStorageAccess() }
        tvStatus.setOnClickListener {
            if (SleepTimer.active) {
                SleepTimer.cancel()
                status(t("Schlaf-Timer abgebrochen", "Sleep timer cancelled"))
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (boxOverlay != null) exitBox() else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        adapter = CardAdapter(
            onPlay = { playCard(it) },
            onLong = { editor.show(it) },
            onImage = { coverMenu(it) },
            coverOf = { CoverStore.get(this, it.number) },
            countOf = { fileCount[it.number] ?: 0 }
        )
        editor = CardEditor(this, onSaved = { loadCards(showToast = false) }, onWrite = { startWrite(it) })
        imageSearch = ImageSearch(this) { coverNr = -1; adapter.notifyDataSetChanged() }
        handler.postDelayed(idleCheck, 10_000)
        btnAdd.setOnClickListener { editor.show(null) }
        findViewById<RecyclerView>(R.id.rvCards).apply {
            layoutManager = GridLayoutManager(this@MainActivity, spanCount())
            adapter = this@MainActivity.adapter
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }

        current = this
        if (AppPrefs.overLock(this)) showOverLock()
        ContextCompat.registerReceiver(
            this, screenOnReceiver, android.content.IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        VolumeLimit.register(this)
        lastOffState = Schedule.isOffTime(this)
        handler.postDelayed(nightCheck, 60_000)
        Schedule.reschedule(this)

        loadCards(showToast = false)
        if (savedInstanceState == null) rootLayout.post { enterBox() } // always start in box mode
        handleIntent(intent)
    }

    private fun bindViews() {
        rootLayout = findViewById(R.id.root)
        contentLayout = findViewById(R.id.content)
        playerCard = findViewById(R.id.playerCard)
        // player bar swallows taps in box mode: a tap only wakes it, never exits box mode (no double tap here)
        (playerCard as com.google.android.material.card.MaterialCardView).apply {
            rippleColor = ColorStateList.valueOf(Color.TRANSPARENT)
            setOnClickListener { if (boxOverlay != null && !boxAwake) wakeBox() else if (boxOverlay != null) lastBoxTap = 0L }
        }
        topButtons = findViewById(R.id.topButtons)
        tvCard = findViewById(R.id.tvCard)
        tvTrack = findViewById(R.id.tvTrack)
        tvTime = findViewById(R.id.tvTime)
        tvStatus = findViewById(R.id.tvStatus)
        tvTop = findViewById(R.id.tvTop)
        tvLine2 = findViewById(R.id.tvLine2)
        battery = findViewById(R.id.battery)
        tvTitle = findViewById(R.id.tvTitle)
        ivCover = findViewById(R.id.ivCover)
        pbMini = findViewById(R.id.pbMini)
        btnPlay = findViewById(R.id.btnPlay)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnBox = findViewById(R.id.btnBox)
        btnRotate = findViewById(R.id.btnRotate)
        btnRotate.visibility = View.GONE // orientation is automatic (box mode: always landscape)
        btnReload = findViewById(R.id.btnReload)
        btnSettings = findViewById(R.id.btnPlayerSettings)
        btnAdd = findViewById(R.id.btnAdd)
        btnPermission = findViewById(R.id.btnPermission)
    }

    private fun applyTexts() {
        btnReload.text = t("Neu laden", "Reload")
        btnSettings.text = t("Einstellungen", "Settings")
        btnAdd.text = t("+ Karte", "+ Card")
        btnPermission.text = t("Speicherzugriff erlauben", "Allow storage access")
        updateToggleTexts()
    }

    private fun updateToggleTexts() {
        btnBox.text = if (boxOverlay == null) t("Box-Modus", "Box mode") else t("Box beenden", "Exit box")
    }

    private fun spanCount() =
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 2 else 1

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        (findViewById<RecyclerView>(R.id.rvCards).layoutManager as? GridLayoutManager)?.spanCount = spanCount()
        rootLayout.post { applyPlayerLayout(); topButtons.post { applyPlayerLayout() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = try { future.get() } catch (e: Exception) { null }
            pendingCard?.let { pendingCard = null; onCardScanned(it, force = true) }
            updateUi()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResume() {
        super.onResume()
        isFront = true
        lastInteraction = SystemClock.elapsedRealtime()
        if (AppPrefs.language(this) == "en" != languageEn) { recreate(); return }
        applyOrientation() // e.g. back from Amazon Music (portrait) -> landscape again
        UiUtil.hideSystemBars(this)
        loadCards(showToast = false)
        btnPermission.visibility = if (hasStorageAccess()) View.GONE else View.VISIBLE
        enableReader()
        handler.removeCallbacks(ticker)
        if (boxOverlay == null || playerPrefs.imBoxModus) handler.post(ticker)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastInteraction = SystemClock.elapsedRealtime()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        lastInteraction = SystemClock.elapsedRealtime()
        if (hasFocus) UiUtil.hideSystemBars(this)
    }

    override fun onPause() {
        super.onPause()
        isFront = false
        try { nfc?.disableReaderMode(this) } catch (_: Exception) {}
        handler.removeCallbacks(ticker)
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    override fun onDestroy() {
        if (current === this) current = null
        try { unregisterReceiver(screenOnReceiver) } catch (_: Exception) {}
        VolumeLimit.unregister(this)
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        handler.removeCallbacks(nightCheck)
        handler.removeCallbacks(idleCheck)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- lock screen / schedule

    /** Off time: dark box mode, optionally stop the music, switch the display off. */
    fun goDark(stopMusic: Boolean = false) {
        if (stopMusic) StreamStarter.key(this, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
        if (boxOverlay == null) enterBox() else dimBox()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!turnDisplayOff()) {
            status(t("Aus-Zeit: Display geht mit der normalen Bildschirm-Zeitsperre aus", "Off time: display turns off with the normal screen timeout"))
        }
    }

    /**
     * Display off now (device admin). "Turn screen on" is reset first – otherwise Android
     * switches the display straight back on when the app shows above the lock screen.
     */
    private fun turnDisplayOff(): Boolean {
        if (!Schedule.isAdmin(this)) return false
        if (Build.VERSION.SDK_INT >= 27) setTurnScreenOn(false)
        else {
            @Suppress("DEPRECATION")
            window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        appTurnedOff = SystemClock.elapsedRealtime()
        return Schedule.lockNow(this)
    }

    private fun isInteractive() = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive

    private fun showOverLock() {
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        try {
            (getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).requestDismissKeyguard(this, null)
        } catch (_: Exception) {
        }
    }

    /** Opened by autostart / schedule / screen on. */
    private fun wakeUp() {
        if (Build.VERSION.SDK_INT >= 27) setTurnScreenOn(true)
        else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        showOverLock()
        // very first start: no box mode (and no automatic box mode) – the app is being set up
        firstSession = !AppPrefs.prefs(this).getBoolean("launchedBefore", false)
        if (firstSession) AppPrefs.set(this, "launchedBefore", true)
        else if (AppPrefs.startBox(this)) rootLayout.post { enterBox() }
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------------------------------------------------------------- NFC

    private fun enableReader() {
        val a = nfc
        when {
            a == null -> status(t("Dieses Handy hat kein NFC", "This phone has no NFC"))
            !a.isEnabled -> status(t("NFC ist ausgeschaltet – bitte einschalten", "NFC is off – please switch it on"))
            else -> {
                val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 1500) }
                a.enableReaderMode(this, { tag ->
                    val w = pendingWrite
                    if (w != null) {
                        val err = NfcUtil.write(tag, w, packageName)
                        runOnUiThread { onWriteResult(w, err) }
                    } else {
                        val nr = NfcUtil.readNumber(tag)
                        runOnUiThread {
                            if (nr != null) onCardScanned(nr)
                            else status(t("Karte ohne Nummer – in der Liste auf „Schreiben“ tippen", "Card without number – tap “Write” in the list"))
                        }
                    }
                }, NfcAdapter.FLAG_READER_NFC_A, extras)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra(EXTRA_WAKE, false)) {
            intent.removeExtra(EXTRA_WAKE)
            wakeUp()
            return
        }
        val writeNr = intent.getIntExtra(EXTRA_WRITE, -1)
        if (writeNr > 0) {
            intent.removeExtra(EXTRA_WRITE)
            loadCards(showToast = false)
            rootLayout.post { startWrite(writeNr) }
            return
        }
        if (intent.action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
            intent.action != NfcAdapter.ACTION_TAG_DISCOVERED &&
            intent.action != NfcAdapter.ACTION_TECH_DISCOVERED
        ) return
        val msgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        val nr = msgs?.firstNotNullOfOrNull { NfcUtil.parseNumber(it as? NdefMessage) }
        intent.action = null
        if (nr != null) onCardScanned(nr)
    }

    private fun startWrite(nr: Int) {
        if (nfc?.isEnabled != true) {
            toast(t("NFC ist nicht verfügbar oder ausgeschaltet", "NFC is not available or switched off"))
            return
        }
        if (boxOverlay != null) exitBox()
        pendingWrite = nr
        writeDialog = MaterialAlertDialogBuilder(this)
            .setTitle(t("Karte $nr beschreiben", "Write card $nr"))
            .setMessage(t("Halte jetzt die Karte an die Rückseite des Handys und warte auf die Bestätigung.", "Hold the card to the back of the phone now and wait for the confirmation."))
            .setNegativeButton(t("Abbrechen", "Cancel")) { _, _ -> pendingWrite = null }
            .setOnCancelListener { pendingWrite = null }
            .show()
    }

    private fun onWriteResult(nr: Int, err: String?) {
        if (err == null) {
            pendingWrite = null
            writeDialog?.dismiss()
            toast(t("Karte $nr beschrieben ✓", "Card $nr written ✓"))
            lastScanNr = nr
            lastScanTime = SystemClock.elapsedRealtime()
        } else {
            toast(err)
        }
    }

    // ---------------------------------------------------------------- playback

    private fun onCardScanned(nr: Int, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        // card still inserted and found again (e.g. back from Amazon/Spotify): keep the stream running
        val known = cards[nr]
        if (!force && known != null && known.isStream && currentCard?.number == nr &&
            getSystemService(android.media.AudioManager::class.java).isMusicActive) {
            lastScanNr = nr; lastScanTime = now
            return
        }
        if (!force && nr == lastScanNr && now - lastScanTime < 2500) return
        lastScanNr = nr
        lastScanTime = now
        lastInteraction = now
        if (cards.isEmpty()) loadCards(showToast = false)
        val c = cards[nr]
        if (c == null) {
            status(t("Karte $nr ist noch nicht angelegt („+ Karte“)", "Card $nr is not set up yet (“+ Card”)"))
            toast(t("Unbekannte Karte $nr", "Unknown card $nr"))
            return
        }
        playCard(c)
    }

    private fun playCard(card: Card) {
        if (card.mode == MODE_FREEZEDANCE && !card.isStream) {
            val on = FreezeDance.toggle(this, card)
            Speaker.say(this, if (on) t("Stoptanz an", "Freeze dance on") else t("Stoptanz aus", "Freeze dance off"))
            status(if (on) t("Stoptanz läuft – Karte nochmal auflegen zum Beenden", "Freeze dance on – scan again to stop")
                   else t("Stoptanz beendet", "Freeze dance stopped"))
            return
        }
        if (card.mode == MODE_TIMER && !card.isStream) {
            if (SleepTimer.active && SleepTimer.cardNumber == card.number) {
                SleepTimer.cancel()
                Speaker.say(this, t("Timer aus", "Timer off"))
                status(t("Schlaf-Timer aus", "Sleep timer off"))
            } else {
                SleepTimer.start(this, card.minutes, card.fade, card.number)
                Speaker.say(this, t("Timer an", "Timer on"))
                status(t("Schlaf-Timer ${card.minutes} min – Karte nochmal auflegen oder hier tippen zum Abbrechen", "Sleep timer ${card.minutes} min – scan again or tap here to cancel"))
            }
            return
        }
        StreamStarter.cancel()
        if (card.isStream) { startStream(card); return }

        val c = controller
        if (c == null) { pendingCard = card.number; return }

        // same card again while playing in order / single -> keep playing
        if (currentCard?.number == card.number && c.isPlaying && card.mode in setOf(MODE_ROW, MODE_SINGLE)) {
            status(t("Karte ${card.number} läuft bereits", "Card ${card.number} is already playing"))
            return
        }
        val all = card.files()
        val files = if (card.mode == MODE_SINGLE) all.filter { it.name == card.file } else all
        if (files.isEmpty()) {
            status(t("Karte ${card.number}: keine Audiodatei gefunden (${card.folder})", "Card ${card.number}: no audio file found (${card.folder})"))
            toast(t("Ordner/Datei nicht gefunden", "Folder/file not found"))
            return
        }
        saveCurrentState()
        currentCard = card

        // statesave: continue at the last track, 10 s before the saved position
        var startIndex = 0
        var startPos = 0L
        if (card.statesave) {
            val s = StateStore.load(this, card.number)
            if (s != null) {
                val i = files.indexOfFirst { it.name == s.first }
                if (i >= 0) { startIndex = i; startPos = (s.second - StateStore.REWIND_MS).coerceAtLeast(0) }
            } else StateStore.legacy(this, card.number)?.let { (i, p) ->
                if (i in files.indices) { startIndex = i; startPos = (p - StateStore.REWIND_MS).coerceAtLeast(0) }
            }
        }

        fun id(f: File, i: Int) = if (card.statesave) StateStore.mediaId(card.number, f.name) else "p:${card.number}:$i"

        when (card.mode) {
            MODE_RANDOM -> {
                val items = files.mapIndexed { i, f -> item(f, id(f, i)) }
                c.shuffleModeEnabled = true
                c.repeatMode = Player.REPEAT_MODE_ALL
                val first = if (card.statesave && StateStore.load(this, card.number) != null) startIndex else files.indices.random()
                c.setMediaItems(items, first, if (first == startIndex) startPos else 0L)
            }
            MODE_SINGLE_RANDOM -> {
                val f = files.random()
                c.shuffleModeEnabled = false
                c.repeatMode = Player.REPEAT_MODE_OFF
                c.setMediaItems(listOf(item(f, "p:${card.number}:0")), 0, 0L)
            }
            MODE_SINGLE -> {
                c.shuffleModeEnabled = false
                c.repeatMode = Player.REPEAT_MODE_OFF
                c.setMediaItems(listOf(item(files[0], id(files[0], 0))), 0, startPos)
            }
            else -> { // row
                val items = files.mapIndexed { i, f -> item(f, id(f, i)) }
                c.shuffleModeEnabled = false
                c.repeatMode = Player.REPEAT_MODE_OFF
                c.setMediaItems(items, startIndex, startPos)
            }
        }
        c.prepare()
        c.play()
        status("${card.number}: ${card.title()} · ${card.label()}")
    }

    private fun saveCurrentState() {
        val c = controller ?: return
        val (nr, file) = StateStore.parse(c.currentMediaItem?.mediaId) ?: return
        if (c.playbackState == Player.STATE_ENDED) return
        StateStore.save(this, nr, file, c.currentPosition)
    }

    private fun item(f: File, id: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setUri(Uri.fromFile(f))
            .setMediaMetadata(MediaMetadata.Builder().setDisplayTitle(f.nameWithoutExtension).build())
            .build()

    private fun startStream(card: Card) {
        val appName = if (card.isSpotify) "Spotify" else "Amazon Music"
        if (card.link.isNullOrBlank()) {
            status(t("Karte ${card.number}: kein Link eingetragen", "Card ${card.number}: no link set"))
            return
        }
        saveCurrentState()
        controller?.run { stop(); clearMediaItems() }
        currentCard = card
        coverNr = -1
        if (StreamStarter.launch(this, card)) status(t("Karte ${card.number}: $appName wird gestartet", "Card ${card.number}: starting $appName"))
        else toast(t("$appName ist nicht installiert", "$appName is not installed"))
    }

    /** Play/pause (0), previous (-1), next (1) for Amazon Music / Spotify via media keys. */
    private fun streamControl(action: Int) {
        StreamStarter.key(this, when (action) {
            -1 -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            1 -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            else -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        })
        handler.postDelayed({ updateUi() }, 400)
    }

    /** Streaming: card name as title, play state from "music active" (no permission needed). */
    private fun updateStreamUi(card: Card, timerText: String) {
        val title = card.name ?: if (card.isSpotify) "Spotify" else "Amazon Music"
        if (tvTitle.text.toString() != title) tvTitle.text = title
        setPlayIcon(getSystemService(android.media.AudioManager::class.java).isMusicActive)
        tvTrack.text = timerText
        pbMini.progress = 0
        tvTime.text = ""
    }

    // ---------------------------------------------------------------- cards

    private fun loadCards(showToast: Boolean) {
        val list = CardStore.load(this)
        cards = list.associateBy { it.number }
        fileCount.clear()
        list.forEach { if (it.usesFolder) fileCount[it.number] = it.files().size }
        adapter.submit(list)
        status(if (list.isEmpty()) t("Noch keine Karten – „+ Karte“ tippen oder im Menü eine JSON importieren", "No cards yet – tap “+ Card” or import a JSON in the menu")
               else t("${list.size} Karten", "${list.size} cards"))
        Thread {
            if (CoverStore.ensureFromFiles(this, list)) runOnUiThread {
                coverNr = -1
                adapter.notifyDataSetChanged()
            }
        }.start()
        if (showToast) toast(t("Aktualisiert", "Updated"))
    }

    private fun doImport(text: String, replace: Boolean) {
        try {
            val n = CardStore.importText(this, text, replace)
            loadCards(showToast = false)
            toast(t("$n Karten importiert", "$n cards imported"))
        } catch (e: Exception) {
            toast(t("Keine gültige Karten-Datei", "Not a valid card file"))
        }
    }

    private fun showSettingsMenu(v: View) {
        android.widget.PopupMenu(this, v).apply {
            menu.add(0, 2, 0, t("Einstellungen (Sprache, Streaming, Berechtigungen …)", "Settings (language, streaming, permissions …)"))
            menu.add(0, 5, 1, t("Streaming-Karten (Amazon / Spotify)", "Streaming cards (Amazon / Spotify)"))
            menu.add(0, 1, 2, t("Player-Leiste anpassen (Größe, Position, Farben)", "Adjust player bar (size, position, colours)"))
            menu.add(0, 3, 3, t("Karten aus JSON importieren", "Import cards from JSON"))
            menu.add(0, 4, 4, t("Karten als JSON exportieren", "Export cards as JSON"))
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> showPlayerSettings()
                    2 -> startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    5 -> startActivity(Intent(this@MainActivity, AmazonActivity::class.java))
                    3 -> importJson.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
                    4 -> try {
                        CardStore.exportTo(this@MainActivity, CardJson.exportFile)
                        toast(t("Gespeichert: Download/cards_export.json", "Saved: Download/cards_export.json"))
                    } catch (e: Exception) {
                        toast(t("Export fehlgeschlagen – Speicherzugriff erlaubt?", "Export failed – storage access granted?"))
                    }
                }
                true
            }
            show()
        }
    }

    private fun coverMenu(card: Card) {
        val online = t("Bild online suchen", "Search picture online")
        val gallery = t("Bild aus Galerie wählen", "Choose picture from gallery")
        val reread = t("Bild aus MP3 neu einlesen", "Read picture from MP3 again")
        val remove = t("Bild entfernen", "Remove picture")
        val opts = mutableListOf(gallery)
        if (card.usesFolder) opts += reread
        opts.add(minOf(2, opts.size), online)
        opts += remove
        MaterialAlertDialogBuilder(this)
            .setTitle(t("Bild für Karte ${card.number}", "Picture for card ${card.number}"))
            .setItems(opts.toTypedArray()) { _, i ->
                when (opts[i]) {
                    online -> imageSearch.show(card)
                    gallery -> {
                        pickCoverFor = card.number
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    reread -> Thread {
                        CoverStore.remove(this, card.number)
                        CoverStore.resetTried(this, card.number)
                        val ok = CoverStore.ensureFromFiles(this, listOf(card))
                        runOnUiThread {
                            coverNr = -1
                            adapter.notifyDataSetChanged()
                            toast(if (ok) t("Bild übernommen", "Picture taken over") else t("Keine Bilder in den MP3-Dateien gefunden", "No pictures found in the MP3 files"))
                        }
                    }.start()
                    else -> {
                        CoverStore.remove(this, card.number)
                        coverNr = -1
                        adapter.notifyDataSetChanged()
                    }
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- storage permission

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) loadCards(showToast = false)
    }

    // ---------------------------------------------------------------- box mode

    /**
     * Screen stays on (otherwise Android switches NFC off) but black and at minimum
     * brightness. The player bar and the buttons stay visible (dimmed).
     */
    private fun enterBox() {
        if (boxOverlay != null) return
        applyOrientationFor(box = true) // box mode always in landscape
        val hint = TextView(this).apply {
            text = t("Box-Modus · Karte auflegen\nantippen = heller, nochmal tippen = beenden", "Box mode · scan a card\ntap = brighter, tap again = exit")
            setTextColor(Color.rgb(40, 40, 40))
            gravity = Gravity.CENTER
            textSize = 14f
        }
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setOnClickListener {
                val now = SystemClock.elapsedRealtime()
                when {
                    !boxAwake -> wakeBox()                      // 1st tap: brighter
                    now - lastBoxTap < 3000 -> exitBox()        // 2nd tap within 3 s: exit
                    else -> wakeBox()
                }
                lastBoxTap = now
            }
        }
        boxHint = hint
        boxAwake = false
        handler.postDelayed({ if (!boxAwake) hint.visibility = View.INVISIBLE }, 4000)
        // overlay above the content, below buttons and player bar
        rootLayout.addView(overlay, 1, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        boxOverlay = overlay
        savedDecorBg = window.decorView.background
        window.decorView.setBackgroundColor(Color.BLACK)
        topButtons.alpha = 0.6f
        handler.removeCallbacks(ticker)
        if (playerPrefs.imBoxModus) {
            playerCard.alpha = 0.5f
            handler.post(ticker)
        } else {
            playerCard.visibility = View.GONE
        }
        setLowRefreshRate(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        applyDarkBrightness()
        UiUtil.hideSystemBars(this)
        styleBoxButtons(true)
        updateToggleTexts()
    }

    private fun exitBox() {
        val overlay = boxOverlay ?: return
        rootLayout.removeView(overlay)
        boxOverlay = null
        boxAwake = false
        boxHint = null
        lastInteraction = SystemClock.elapsedRealtime()
        playerCard.visibility = View.VISIBLE
        playerCard.alpha = 1f
        topButtons.alpha = 1f
        window.decorView.background = savedDecorBg ?: android.graphics.drawable.ColorDrawable(resolveBgColor())
        setLowRefreshRate(false)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27 && !AppPrefs.overLock(this)) setShowWhenLocked(false)
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        UiUtil.hideSystemBars(this)
        applyOrientationFor(box = false)
        styleBoxButtons(false)
        updateToggleTexts()
    }

    @Suppress("DEPRECATION")
    private fun setLowRefreshRate(low: Boolean) {
        try {
            val lp = window.attributes
            if (low) {
                val d = (if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay) ?: return
                val cur = d.mode
                val best = d.supportedModes
                    .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                    .minByOrNull { it.refreshRate }
                lp.preferredDisplayModeId = best?.modeId ?: 0
                if (best != null) lp.preferredRefreshRate = best.refreshRate
            } else {
                lp.preferredDisplayModeId = 0
                lp.preferredRefreshRate = 0f
            }
            window.attributes = lp
        } catch (_: Exception) {
        }
    }

    private fun resolveBgColor(): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.colorBackground, tv, true)
        return tv.data
    }

    /** Box mode: always landscape. Otherwise the orientation follows the phone (automatic). */
    private fun applyOrientation() = applyOrientationFor(boxOverlay != null)

    private fun applyOrientationFor(box: Boolean) {
        requestedOrientation = if (box) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR
    }

    // ---------------------------------------------------------------- player bar layout

    private fun mmToPx(mm: Float, vertical: Boolean): Int {
        val dm = resources.displayMetrics
        val dpi = if (vertical) dm.ydpi else dm.xdpi
        val safe = if (dpi < 100f || dpi > 1000f) dm.densityDpi.toFloat() else dpi
        return (mm * safe / 25.4f).toInt()
    }

    private fun applyPlayerLayout() {
        val p = playerPrefs
        val lp = playerCard.layoutParams as FrameLayout.LayoutParams
        lp.width = mmToPx(p.breiteMm, false)
        lp.height = mmToPx(p.hoeheMm, true)
        lp.leftMargin = mmToPx(p.linksMm, false)
        lp.topMargin = mmToPx(p.obenMm, true)
        lp.gravity = Gravity.TOP or Gravity.START
        ivCover.layoutParams = ivCover.layoutParams.apply { width = lp.height }
        playerCard.layoutParams = lp
        val bl = topButtons.layoutParams as FrameLayout.LayoutParams
        val gap = (8 * resources.displayMetrics.density).toInt()
        val fits = if (rootLayout.width > 0 && topButtons.width > 0)
            lp.leftMargin + lp.width + gap + topButtons.width + bl.marginEnd <= rootLayout.width
        else resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (fits) {
            bl.gravity = Gravity.TOP or Gravity.END
            bl.topMargin = lp.topMargin
            bl.leftMargin = 0
        } else {
            bl.gravity = Gravity.TOP or Gravity.START
            bl.topMargin = lp.topMargin + lp.height + gap
            bl.leftMargin = lp.leftMargin
        }
        topButtons.layoutParams = bl
        updateContentPadding()
    }

    private fun updateContentPadding() {
        if (!::playerCard.isInitialized) return
        val pad = (12 * resources.displayMetrics.density).toInt()
        val lp = playerCard.layoutParams as FrameLayout.LayoutParams
        val bl = topButtons.layoutParams as FrameLayout.LayoutParams
        val playerBottom = if (playerPrefs.obenMm < 40f) lp.topMargin + lp.height else 0
        val top = maxOf(playerBottom, bl.topMargin + topButtons.height) + pad / 2
        if (contentLayout.paddingTop != top) contentLayout.setPadding(pad, top, pad, pad / 2)
    }

    private fun maxMm(vertical: Boolean): Int {
        val dm = resources.displayMetrics
        val px = if (vertical) rootLayout.height.takeIf { it > 0 } ?: dm.heightPixels
        else rootLayout.width.takeIf { it > 0 } ?: dm.widthPixels
        val dpi = if (vertical) dm.ydpi else dm.xdpi
        val safe = if (dpi < 100f || dpi > 1000f) dm.densityDpi.toFloat() else dpi
        return (px * 25.4f / safe).toInt().coerceAtLeast(30)
    }

    private fun showPlayerSettings() {
        val start = playerPrefs
        var cur = start
        val maxW = maxMm(false)
        val maxH = maxMm(true)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        /** Slider in 0.5 mm steps. */
        fun slider(title: String, min: Int, max: Int, value: Float, onChange: (Float) -> Unit) {
            val label = TextView(this)
            fun setLabel(v: Float) { label.text = "$title: " + "%.1f".format(v).replace('.', if (I18n.en) '.' else ',') + " mm" }
            setLabel(value)
            val steps = (max - min) * 2
            val sb = SeekBar(this).apply {
                this.max = steps
                progress = ((value - min) * 2).toInt().coerceIn(0, steps)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { val v = min + p / 2f; setLabel(v); onChange(v) }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            }
            box.addView(label); box.addView(sb)
        }
        fun apply(n: PlayerPrefs) { cur = n; playerPrefs = n; applyPlayerLayout(); applyCardTheme() }
        box.addView(TextView(this).apply {
            text = t("Größe und Position", "Size and position")
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
        })
        slider(t("Breite", "Width"), 30, maxW, start.breiteMm) { apply(cur.copy(breiteMm = it)) }
        slider(t("Höhe", "Height"), 10, 60, start.hoeheMm) { apply(cur.copy(hoeheMm = it)) }
        slider(t("Abstand links", "Left offset"), 0, maxW - 10, start.linksMm) { apply(cur.copy(linksMm = it)) }
        slider(t("Abstand oben", "Top offset"), 0, maxH - 10, start.obenMm) { apply(cur.copy(obenMm = it)) }
        box.addView(android.widget.CheckBox(this).apply {
            text = t("Im Box-Modus anzeigen (abgedunkelt)", "Show in box mode (dimmed)")
            isChecked = start.imBoxModus
            setOnCheckedChangeListener { _, c -> apply(cur.copy(imBoxModus = c)) }
        })
        box.addView(TextView(this).apply {
            text = t("Farbschema", "Colour scheme")
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            setPadding(0, pad, 0, 0)
        })
        val rg = android.widget.RadioGroup(this)
        CardTheme.ALL.forEachIndexed { i, th ->
            rg.addView(android.widget.RadioButton(this).apply {
                id = 1000 + i
                text = th.name()
                buttonTintList = android.content.res.ColorStateList.valueOf(th.accent)
            })
        }
        rg.check(1000 + start.farbe.coerceIn(0, CardTheme.ALL.size - 1))
        rg.setOnCheckedChangeListener { _, id -> apply(cur.copy(farbe = id - 1000)) }
        box.addView(rg)
        MaterialAlertDialogBuilder(this)
            .setTitle(t("Player-Leiste", "Player bar"))
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton(t("Speichern", "Save")) { _, _ -> cur.save(this) }
            .setNeutralButton(t("Standard", "Default")) { _, _ -> val d = PlayerPrefs(); d.save(this); playerPrefs = d; applyPlayerLayout(); applyCardTheme() }
            .setNegativeButton(t("Abbrechen", "Cancel")) { _, _ -> playerPrefs = start; applyPlayerLayout(); applyCardTheme() }
            .setOnCancelListener { playerPrefs = start; applyPlayerLayout(); applyCardTheme() }
            .show().also { d ->
                // pop up at the bottom right, so the player bar (top left) stays visible
                d.window?.let { w ->
                    w.setGravity(Gravity.BOTTOM or Gravity.END)
                    w.setDimAmount(0.2f)
                    val dm = resources.displayMetrics
                    w.setLayout((minOf(dm.widthPixels * 0.55f, 460 * dm.density)).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
                }
            }
    }

    // ---------------------------------------------------------------- UI update

    private fun updateUi() {
        val c = controller
        val k = currentCard
        tvCard.text = if (k != null) "%02d · %s".format(k.number, k.title()) else t("Karte auflegen …", "Scan a card …")
        val topText = k?.title() ?: t("Karte auflegen", "Scan a card")
        if (tvTop.text.toString() != topText) tvTop.text = topText
        val line2 = listOfNotNull(
            when {
                FreezeDance.active && FreezeDance.stopped -> t("STOPTANZ – STOPP!", "FREEZE DANCE – FREEZE!")
                FreezeDance.active -> t("STOPTANZ", "FREEZE DANCE")
                else -> null
            },
            if (SleepTimer.active) SleepTimer.label() else null
        ).joinToString("  ·  ")
        if (tvLine2.text.toString() != line2) tvLine2.text = line2
        tvLine2.visibility = if (line2.isEmpty()) View.GONE else View.VISIBLE
        if (coverNr != k?.number) {
            coverNr = k?.number
            val b = k?.let { CoverStore.get(this, it.number) }
            if (b != null) ivCover.setImageBitmap(b) else ivCover.setImageResource(R.drawable.ic_cover_placeholder)
        }
        val timerText = if (SleepTimer.active) SleepTimer.label() else ""
        if (k?.isStream == true) { updateStreamUi(k, timerText); return }

        if (c == null || c.mediaItemCount == 0) {
            val t2 = k?.folder?.substringAfterLast("/") ?: ""
            if (tvTitle.text.toString() != t2) tvTitle.text = t2
            tvTrack.text = timerText
            tvTime.text = ""
            pbMini.progress = 0
            setPlayIcon(false)
            return
        }
        val title = c.mediaMetadata.title ?: c.mediaMetadata.displayTitle
            ?: c.currentMediaItem?.mediaMetadata?.displayTitle ?: k?.folder?.substringAfterLast("/") ?: ""
        if (tvTitle.text.toString() != title.toString()) tvTitle.text = title
        val trackText = if (c.mediaItemCount > 1) t("Titel ${c.currentMediaItemIndex + 1} von ${c.mediaItemCount}", "Track ${c.currentMediaItemIndex + 1} of ${c.mediaItemCount}") else ""
        tvTrack.text = listOf(trackText, timerText).filter { it.isNotEmpty() }.joinToString("   ")
        setPlayIcon(c.isPlaying)
        val dur = c.duration
        if (dur != C.TIME_UNSET && dur > 0) {
            tvTime.text = "${fmt(c.currentPosition)} / ${fmt(dur)}"
            pbMini.progress = (c.currentPosition * 1000 / dur).toInt()
        } else {
            tvTime.text = fmt(c.currentPosition)
        }
    }

    private fun status(s: String) { tvStatus.text = s }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    // ---------------------------------------------------------------- card list

    class CardAdapter(
        private val onPlay: (Card) -> Unit,
        private val onLong: (Card) -> Unit,
        private val onImage: (Card) -> Unit,
        private val coverOf: (Card) -> android.graphics.Bitmap?,
        private val countOf: (Card) -> Int
    ) : RecyclerView.Adapter<CardAdapter.VH>() {

        private var items: List<Card> = emptyList()

        fun submit(list: List<Card>) {
            items = list
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val nr: TextView = v.findViewById(R.id.tvNr)
            val name: TextView = v.findViewById(R.id.tvName)
            val info: TextView = v.findViewById(R.id.tvInfo)
            val edit: Button = v.findViewById(R.id.btnEdit)
            val thumb: ImageView = v.findViewById(R.id.ivThumb)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_karte, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val k = items[pos]
            h.nr.text = "%02d".format(k.number)
            h.name.text = k.title()
            h.edit.setOnClickListener { onLong(k) }
            val b = coverOf(k)
            if (b != null) h.thumb.setImageBitmap(b) else h.thumb.setImageResource(R.drawable.ic_cover_placeholder)
            h.thumb.setOnClickListener { onImage(k) }
            h.info.text = when {
                k.isStream -> (if (k.isSpotify) "Spotify · " else "Amazon · ") + (k.link ?: t("kein Link", "no link"))
                k.mode == MODE_TIMER -> t("Pause nach ${k.minutes} min, ${k.fade} s ausblenden", "Pause after ${k.minutes} min, ${k.fade} s fade")
                k.mode == MODE_FREEZEDANCE -> t("Musik ${k.playMin}–${k.playMax} s, Pause ${k.pauseMin}–${k.pauseMax} s", "Music ${k.playMin}–${k.playMax} s, pause ${k.pauseMin}–${k.pauseMax} s")
                else -> {
                    val n = countOf(k)
                    val folder = k.folder?.substringAfterLast("/Music/", k.folder) ?: "?"
                    val what = if (k.mode == MODE_SINGLE) (k.file ?: "?") else t("$n Dateien", "$n files")
                    if (n == 0) "Music/$folder · ⚠ " + t("keine Dateien gefunden", "no files found")
                    else "Music/$folder · $what · ${k.label()}"
                }
            }
            h.itemView.setOnClickListener { onPlay(k) }
            h.itemView.setOnLongClickListener { onLong(k); true }
        }
    }
}
