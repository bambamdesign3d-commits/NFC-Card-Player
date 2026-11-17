package de.bambamdesign3d.nfccardplayer

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File

// ------------------------------------------------------------------ Language

object I18n {
    var en = false
        private set

    fun init(ctx: Context) {
        en = AppPrefs.language(ctx) == "en"
    }
}

/** Text in the selected language. */
fun t(de: String, en: String) = if (I18n.en) en else de

// ------------------------------------------------------------------ Card

const val SOURCE_LOCAL = "local"
const val SOURCE_AMAZON = "amazon"
const val SOURCE_SPOTIFY = "spotify"

const val MODE_ROW = "row"
const val MODE_RANDOM = "random"
const val MODE_SINGLE = "single"
const val MODE_SINGLE_RANDOM = "singlerandom"
const val MODE_TIMER = "timer"
const val MODE_FREEZEDANCE = "freezedance"

val MODES = listOf(MODE_ROW, MODE_RANDOM, MODE_SINGLE, MODE_SINGLE_RANDOM, MODE_TIMER, MODE_FREEZEDANCE)

data class Card(
    val number: Int,
    val name: String? = null,
    val source: String = SOURCE_LOCAL,
    val mode: String = MODE_ROW,
    val folder: String? = null,
    val file: String? = null,          // single: file name inside folder
    val link: String? = null,          // amazon
    val statesave: Boolean = false,    // remember last track + position
    val minutes: Int = 30,             // timer
    val fade: Int = 30,                // timer: fade-out seconds
    val playMin: Int = 7,              // freezedance: music runs (seconds)
    val playMax: Int = 30,
    val pauseMin: Int = 3,             // freezedance: pause (seconds)
    val pauseMax: Int = 8,
) {

    val isAmazon get() = source == SOURCE_AMAZON
    val isSpotify get() = source == SOURCE_SPOTIFY
    val isStream get() = isAmazon || isSpotify
    val usesFolder get() = !isStream && mode in setOf(MODE_ROW, MODE_RANDOM, MODE_SINGLE, MODE_SINGLE_RANDOM)

    fun title(): String = name ?: label()

    fun label(): String {
        val base = when {
            isAmazon -> "Amazon Music"
            isSpotify -> "Spotify"
            mode == MODE_ROW -> t("Der Reihe nach", "In order")
            mode == MODE_RANDOM -> t("Zufällig", "Random")
            mode == MODE_SINGLE -> t("Einzelne Datei", "Single file")
            mode == MODE_SINGLE_RANDOM -> t("Eine zufällige Datei", "One random file")
            mode == MODE_TIMER -> t("Schlaf-Timer $minutes min", "Sleep timer $minutes min")
            mode == MODE_FREEZEDANCE -> freezeName()
            else -> mode
        }
        return if (usesFolder && statesave) base + " · " + t("merkt Stelle", "resumes") else base
    }

    fun files(): List<File> {
        val dir = File(folder ?: return emptyList())
        val list = dir.listFiles { f -> f.isFile && f.extension.lowercase() in AUDIO_EXT } ?: return emptyList()
        return list.sortedBy { it.name.lowercase() }
    }

    companion object {
        val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")
        fun freezeName() = t("Stoptanz", "Freeze dance")
        fun modeLabel(m: String) = when (m) {
            MODE_ROW -> t("row – der Reihe nach", "row – in order")
            MODE_RANDOM -> t("random – zufällig (läuft weiter)", "random – shuffle (keeps playing)")
            MODE_SINGLE -> t("single – eine bestimmte Datei", "single – one specific file")
            MODE_SINGLE_RANDOM -> t("singlerandom – eine zufällige Datei, dann Stopp", "singlerandom – one random file, then stop")
            MODE_TIMER -> t("timer – Schlaf-Timer", "timer – sleep timer")
            MODE_FREEZEDANCE -> t("freezedance – Stoptanz", "freezedance – freeze dance")
            else -> m
        }
    }
}

// ------------------------------------------------------------------ JSON

object CardJson {
    /** First-start import: Download/cards.json (or the older karten.json). */
    val importFile: File
        get() {
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val cards = File(dl, "cards.json")
            return if (cards.exists()) cards else File(dl, "karten.json")
        }
    val exportFile: File
        get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "cards_export.json")

    private fun yes(o: JSONObject, key: String): Boolean = when (val v = o.opt(key)) {
        is Boolean -> v
        is String -> v.trim().lowercase() in setOf("yes", "ja", "true", "1", "y")
        is Number -> v.toInt() != 0
        else -> false
    }

    /** Reads the new format and also the old German format (typ/ordner/minuten). */
    fun parse(text: String): List<Card> {
        val json = JSONObject(text)
        val list = mutableListOf<Card>()
        for (key in json.keys()) {
            val nr = key.trim().toIntOrNull() ?: continue
            val o = json.optJSONObject(key) ?: continue
            var mode = o.optString("mode").lowercase()
            var source = o.optString("source", SOURCE_LOCAL).lowercase()
            var statesave = yes(o, "statesave")
            if (mode.isEmpty()) {
                when (o.optString("typ").lowercase()) {
                    "zufall" -> mode = MODE_RANDOM
                    "folge" -> mode = MODE_SINGLE_RANDOM
                    "hoerbuch" -> { mode = MODE_ROW; statesave = true }
                    "amazon" -> { mode = MODE_ROW; source = SOURCE_AMAZON }
                    "timer" -> mode = MODE_TIMER
                    "stoptanz" -> mode = MODE_FREEZEDANCE
                    else -> mode = MODE_ROW
                }
            }
            if (mode == "single random" || mode == "single_random") mode = MODE_SINGLE_RANDOM
            if (mode !in MODES) mode = MODE_ROW
            val link = o.optString("link").ifBlank { null }
            if (source != SOURCE_AMAZON && link != null && o.optString("folder").isBlank() && o.optString("ordner").isBlank()) {
                source = SOURCE_AMAZON
            }
            list += Card(
                number = nr,
                name = o.optString("name").ifBlank { null },
                source = when {
                    source == SOURCE_SPOTIFY || (link?.contains("spotify") == true) -> SOURCE_SPOTIFY
                    source == SOURCE_AMAZON -> SOURCE_AMAZON
                    else -> SOURCE_LOCAL
                },
                mode = mode,
                folder = o.optString("folder").ifBlank { o.optString("ordner") }.ifBlank { null },
                file = o.optString("file").ifBlank { null },
                link = link,
                statesave = statesave,
                minutes = o.optInt("minutes", o.optInt("minuten", 30)),
                fade = o.optInt("fade", 30),
                playMin = o.optInt("playMin", 7),
                playMax = o.optInt("playMax", 30),
                pauseMin = o.optInt("pauseMin", 3),
                pauseMax = o.optInt("pauseMax", 8)
            )
        }
        return list.sortedBy { it.number }
    }

    fun toJson(list: List<Card>): String {
        val root = JSONObject()
        for (c in list.sortedBy { it.number }) {
            val o = JSONObject()
            c.name?.let { o.put("name", it) }
            if (c.isStream) {
                o.put("source", c.source)
                o.put("link", c.link ?: "")
            } else {
                o.put("mode", c.mode)
                when (c.mode) {
                    MODE_TIMER -> { o.put("minutes", c.minutes); o.put("fade", c.fade) }
                    MODE_FREEZEDANCE -> {
                        o.put("playMin", c.playMin); o.put("playMax", c.playMax)
                        o.put("pauseMin", c.pauseMin); o.put("pauseMax", c.pauseMax)
                    }
                    else -> {
                        c.folder?.let { o.put("folder", it) }
                        if (c.mode == MODE_SINGLE) c.file?.let { o.put("file", it) }
                        o.put("statesave", if (c.statesave) "yes" else "no")
                    }
                }
            }
            root.put(c.number.toString(), o)
        }
        return root.toString(1)
    }
}

// ------------------------------------------------------------------ Card store (inside the app)

object CardStore {
    private var cache: List<Card>? = null
    private fun file(ctx: Context) = File(ctx.filesDir, "karten.json")

    @Synchronized
    fun load(ctx: Context): List<Card> {
        cache?.let { return it }
        if (!file(ctx).exists() && !firstRun(ctx)) return emptyList()
        var l = try { CardJson.parse(file(ctx).readText(Charsets.UTF_8)) } catch (e: Exception) { emptyList() }
        val prefs = ctx.getSharedPreferences("cards", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("defaults_v1", false)) {
            // preset cards 996–999 (only numbers that are still free)
            val used = l.map { it.number }.toSet()
            val add = defaults(ctx).filter { it.number !in used }
            if (add.isNotEmpty()) {
                l = (l + add).sortedBy { it.number }
                file(ctx).writeText(CardJson.toJson(l), Charsets.UTF_8)
            }
            prefs.edit().putBoolean("defaults_v1", true).apply()
        }
        cache = l
        return l
    }

    /** 996 test music (bundled), 997 freeze dance, 998 timer 30, 999 timer 60. */
    fun defaults(ctx: Context): List<Card> {
        val dir = File(ctx.filesDir, "testmusic").apply { mkdirs() }
        val f = File(dir, "Testmusik.ogg")
        if (dir.listFiles()?.none { it.extension.lowercase() in Card.AUDIO_EXT } != false) try {
            ctx.resources.openRawResource(R.raw.testmusik).use { input -> f.outputStream().use { input.copyTo(it) } }
        } catch (_: Exception) {
        }
        return listOf(
            Card(996, t("Testmusik", "Test music"), mode = MODE_ROW, folder = dir.absolutePath),
            Card(997, t("Stoptanz", "Freeze dance"), mode = MODE_FREEZEDANCE),
            Card(998, t("Schlaf-Timer 30 min", "Sleep timer 30 min"), mode = MODE_TIMER, minutes = 30),
            Card(999, t("Schlaf-Timer 60 min", "Sleep timer 60 min"), mode = MODE_TIMER, minutes = 60)
        )
    }

    @Synchronized
    fun save(ctx: Context, list: List<Card>) {
        val sorted = list.sortedBy { it.number }
        file(ctx).writeText(CardJson.toJson(sorted), Charsets.UTF_8)
        cache = sorted
    }

    fun put(ctx: Context, c: Card, oldNumber: Int? = null) =
        save(ctx, load(ctx).filter { it.number != c.number && it.number != oldNumber } + c)

    fun remove(ctx: Context, nr: Int) = save(ctx, load(ctx).filter { it.number != nr })

    fun get(ctx: Context, nr: Int) = load(ctx).firstOrNull { it.number == nr }

    fun nextFree(ctx: Context, range: IntRange = 1..999): Int {
        val used = load(ctx).map { it.number }.toSet()
        return range.firstOrNull { it !in used } ?: range.last
    }

    fun importText(ctx: Context, text: String, replace: Boolean): Int {
        val new = CardJson.parse(text)
        val nrs = new.map { it.number }.toSet()
        save(ctx, if (replace) new else load(ctx).filter { it.number !in nrs } + new)
        return new.size
    }

    fun exportTo(ctx: Context, f: File) = f.writeText(CardJson.toJson(load(ctx)), Charsets.UTF_8)

    @Synchronized
    fun invalidate() { cache = null }

    /** First start: take over Download/cards.json once (if present and readable). */
    private fun firstRun(ctx: Context): Boolean {
        val d = CardJson.importFile
        val list = try {
            when {
                !d.exists() -> emptyList()
                d.canRead() -> CardJson.parse(d.readText(Charsets.UTF_8))
                else -> return false
            }
        } catch (e: Exception) {
            emptyList()
        }
        file(ctx).writeText(CardJson.toJson(list), Charsets.UTF_8)
        cache = null
        AmazonStore.migrateLegacy(ctx, list)
        return true
    }
}

// ------------------------------------------------------------------ statesave

/** Remembers last file and position per card (statesave = yes). */
object StateStore {
    private const val PREFS = "state"
    private const val PREFIX = "s:"
    const val REWIND_MS = 10_000L

    fun mediaId(nr: Int, file: String) = "$PREFIX$nr:$file"

    fun parse(id: String?): Pair<Int, String>? {
        if (id == null || !id.startsWith(PREFIX)) return null
        val rest = id.removePrefix(PREFIX)
        val nr = rest.substringBefore(":").toIntOrNull() ?: return null
        return nr to rest.substringAfter(":")
    }

    fun save(ctx: Context, nr: Int, file: String, pos: Long) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("f_$nr", file).putLong("p_$nr", pos).apply()
    }

    fun load(ctx: Context, nr: Int): Pair<String, Long>? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val f = p.getString("f_$nr", null) ?: return null
        return f to p.getLong("p_$nr", 0L)
    }

    fun clear(ctx: Context, nr: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("f_$nr").remove("p_$nr").apply()
        legacyPrefs(ctx).edit().remove("idx_$nr").remove("pos_$nr").apply()
    }

    private fun legacyPrefs(ctx: Context) = ctx.getSharedPreferences("hoerbuch", Context.MODE_PRIVATE)

    /** Progress from older app versions (index based). */
    fun legacy(ctx: Context, nr: Int): Pair<Int, Long>? {
        val p = legacyPrefs(ctx)
        if (!p.contains("idx_$nr")) return null
        return p.getInt("idx_$nr", 0) to p.getLong("pos_$nr", 0L)
    }
}
