package de.bambamdesign3d.nfccardplayer

import android.content.Context
import org.json.JSONObject

/** Streaming cards (Amazon Music / Spotify), stored in the card store. */
object AmazonStore {
    private const val PREFS = "amazon"
    private const val KEY = "karten"

    data class Entry(val number: Int, val name: String, val link: String, val source: String = SOURCE_AMAZON)

    fun sourceFor(link: String) = if (link.contains("spotify", true)) SOURCE_SPOTIFY else SOURCE_AMAZON

    fun all(ctx: Context): List<Entry> =
        CardStore.load(ctx).filter { it.isStream }.map { Entry(it.number, it.name ?: "", it.link ?: "", it.source) }

    fun put(ctx: Context, e: Entry, oldNumber: Int? = null) {
        CardStore.put(ctx, Card(e.number, e.name.ifBlank { null }, sourceFor(e.link), link = e.link), oldNumber)
    }

    fun remove(ctx: Context, number: Int) = CardStore.remove(ctx, number)

    /** Amazon entries from older app versions -> card store (once). */
    fun migrateLegacy(ctx: Context, existing: List<Card>) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return
        val o = try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
        val list = existing.toMutableList()
        for (k in o.keys()) {
            val nr = k.toIntOrNull() ?: continue
            val e = o.optJSONObject(k) ?: continue
            list.removeAll { it.number == nr }
            list += Card(nr, e.optString("name").ifBlank { null }, SOURCE_AMAZON, link = e.optString("link"))
        }
        CardStore.save(ctx, list)
        prefs.edit().remove(KEY).apply()
    }

    /** Ersten Link aus einem geteilten Text holen. */
    fun extractLink(text: String): String? =
        Regex("""https?://\S+""").find(text)?.value?.trimEnd('.', ',', ')', '"', '“', '”')

    /** Namen aus dem geteilten Text raten (Text in Anführungszeichen oder Text ohne Link). */
    fun guessName(text: String): String {
        Regex("""[„"“«]([^"“”»]{2,80})["“”»]""").find(text)?.let { return it.groupValues[1].trim() }
        val link = extractLink(text) ?: return text.trim().take(60)
        return text.replace(link, "")
            .replace(Regex("""(?i)(auf|on|bei)\s+(amazon\s+music|spotify).*"""), "")
            .replace(Regex("""(?i)^(hör\w*|listen to|check out)\s+(dir\s+)?"""), "")
            .trim(' ', ':', '-', '\n').take(60)
    }

    /** Next free card number, starting at 1. */
    fun nextFree(used: Set<Int>): Int = (1..999).firstOrNull { it !in used } ?: 999
}
