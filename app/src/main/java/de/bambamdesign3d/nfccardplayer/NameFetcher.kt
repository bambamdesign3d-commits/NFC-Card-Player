package de.bambamdesign3d.nfccardplayer

import java.net.HttpURLConnection
import java.net.URL

/**
 * Name and preview picture of a shared Amazon Music / Spotify playlist, taken directly from
 * the service: Spotify via its oEmbed interface, Amazon via the page's og:title / og:image.
 */
object NameFetcher {

    data class Info(val name: String?, val imageUrl: String?)

    // Amazon serves link previews (og:title / og:image) to messenger/social link crawlers,
    // the normal browser page is an empty web app. So several user agents are tried.
    private val AGENTS = listOf(
        "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)",
        "WhatsApp/2.23.20.0",
        "Mozilla/5.0 (compatible; Discordbot/2.0; +https://discordapp.com)",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    )

    fun fetchInfo(link: String): Info {
        if (link.contains("spotify.com")) spotifyOEmbed(link)?.let { if (it.imageUrl != null) return it }
        var name: String? = null
        for (ua in AGENTS) {
            val html = fetchHtml(link, ua) ?: continue
            if (name == null) name = parse(html)
            val img = ogImage(html) ?: amazonImage(html)
            if (img != null) return Info(name, img)
        }
        // last resort: cover search by name (iTunes)
        val img = name?.let { n -> ImageSearch.search(n).firstOrNull()?.big }
        return Info(name, img)
    }

    /** First product picture on an Amazon page (m.media-amazon.com/images/I/…). */
    private fun amazonImage(html: String): String? =
        Regex("""https://m\.media-amazon\.com/images/I/[A-Za-z0-9+%._-]+\.(?:jpg|png)""").find(html)?.value

    private fun spotifyOEmbed(link: String): Info? = try {
        val clean = link.substringBefore("?")
        val json = fetchHtml("https://open.spotify.com/oembed?url=" + java.net.URLEncoder.encode(clean, "UTF-8")) ?: null
        json?.let {
            val o = org.json.JSONObject(it)
            Info(o.optString("title").ifBlank { null }?.take(60), o.optString("thumbnail_url").ifBlank { null })
        }
    } catch (e: Exception) {
        null
    }

    private fun ogImage(html: String): String? =
        (Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:image["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
            ?: Regex("""<meta[^>]+name=["']twitter:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1))?.let { unescape(it) }

    private fun fetchHtml(link: String, agent: String = AGENTS.last()): String? {
        var url = link
        repeat(5) {
            val con = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", agent)
                setRequestProperty("Accept-Language", "de-DE,de;q=0.9")
            }
            try {
                val code = con.responseCode
                if (code in 300..399) {
                    val loc = con.getHeaderField("Location") ?: return null
                    url = URL(URL(url), loc).toString()
                    return@repeat
                }
                if (code != 200) return null
                val html = con.inputStream.use { ins ->
                    val buf = ByteArray(400_000)
                    var total = 0
                    while (total < buf.size) {
                        val n = ins.read(buf, total, buf.size - total)
                        if (n <= 0) break
                        total += n
                    }
                    String(buf, 0, total, Charsets.UTF_8)
                }
                return html
            } finally {
                con.disconnect()
            }
        }
        return null
    }

    private fun parse(html: String): String? {
        val raw = Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:title["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
            ?: Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: return null
        val name = unescape(raw)
            .replace(Regex("""(?i)\s*[|\-–]\s*amazon music.*$"""), "")
            .replace(Regex("""(?i)\s+(auf|bei|on)\s+amazon music.*$"""), "")
            .replace(Regex("""(?i)^amazon music\s*[:|\-–]\s*"""), "")
            .replace(Regex("""(?i)^(playlist|album)\s*[:\-–]\s*"""), "")
            .replace(Regex("""(?i)\s*[|\-–]\s*spotify.*$"""), "")
            .replace(Regex("""(?i)\s*[-–]\s*(playlist|album|single|song)\s+(by|von)\s+.*$"""), "")
            .trim()
        if (name.isBlank() || name.equals("Amazon Music", true) || name.equals("Spotify", true) || name.contains("amazon.de", true)) return null
        return name.take(60)
    }

    private fun unescape(s: String) = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }
}
