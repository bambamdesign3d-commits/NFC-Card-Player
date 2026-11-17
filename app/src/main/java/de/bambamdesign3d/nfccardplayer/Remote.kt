package de.bambamdesign3d.nfccardplayer

import android.content.Context
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Remote control & sync between two NFC Card Player phones in the same Wi-Fi.
 *
 * The box shares itself (small TCP server, found via Android's network service discovery).
 * Another phone connects with the box's PIN and can then
 *  - control playback (play/pause, previous, next, start a card, volume),
 *  - fetch all cards + settings (+ pictures) from the box, or send its own to the box.
 *
 * Protocol: one JSON line request -> one JSON line response, plain socket (local network only).
 */
object Remote {
    const val PORT = 48123
    const val SERVICE_TYPE = "_nfccardplayer._tcp."

    /** settings that belong to one phone and are never transferred */
    private val LOCAL_KEYS = setOf("launchedBefore", "remoteEnabled", "remotePin", "remoteName")

    // ------------------------------------------------------------------ prefs

    private fun p(ctx: Context) = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = p(ctx).getBoolean("remoteEnabled", false)

    fun pin(ctx: Context): String {
        p(ctx).getString("remotePin", null)?.let { return it }
        val pin = (1000..9999).random().toString()
        p(ctx).edit().putString("remotePin", pin).apply()
        return pin
    }

    fun newPin(ctx: Context) { p(ctx).edit().remove("remotePin").apply(); pin(ctx) }

    fun name(ctx: Context) = p(ctx).getString("remoteName", null)?.takeIf { it.isNotBlank() }
        ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    fun setName(ctx: Context, n: String) = p(ctx).edit().putString("remoteName", n.trim()).apply()

    /** Wi-Fi IPv4 address of this phone (null if not in a network). */
    fun ip(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------ server (the box)

    private var server: ServerSocket? = null
    /** keeps Wi-Fi awake (no power-save delays) while the box is shared – otherwise connections drop when dark */
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var nsdReg: NsdManager.RegistrationListener? = null

    fun setEnabled(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("remoteEnabled", on).apply()
        if (on) start(ctx) else stop(ctx)
    }

    @Synchronized
    fun start(ctx: Context) {
        if (server != null || !enabled(ctx)) return
        val app = ctx.applicationContext
        val ss = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
        } catch (e: Exception) {
            return
        }
        server = ss
        thread(name = "remote-server", isDaemon = true) {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { handle(app, s) }
            }
        }
        register(app)
        try {
            val wm = app.getSystemService(android.net.wifi.WifiManager::class.java)
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "nfccardplayer:remote").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun stop(ctx: Context) {
        try { server?.close() } catch (_: Exception) {}
        server = null
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
        nsdReg?.let { try { ctx.getSystemService(NsdManager::class.java).unregisterService(it) } catch (_: Exception) {} }
        nsdReg = null
    }

    fun running() = server != null

    private fun register(ctx: Context) {
        val info = NsdServiceInfo().apply {
            serviceName = name(ctx)
            serviceType = SERVICE_TYPE
            port = PORT
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        try {
            ctx.getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsdReg = l
        } catch (_: Exception) {
        }
    }

    private fun handle(ctx: Context, s: Socket) {
        s.use { sock ->
            try {
                sock.soTimeout = 30_000
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                val line = reader.readLine() ?: return
                val req = JSONObject(line)
                val res = if (req.optString("pin") != pin(ctx)) {
                    Thread.sleep(800) // slow down guessing
                    JSONObject().put("ok", false).put("error", "pin")
                } else answer(ctx, req)
                val w = OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8)
                w.write(res.toString()); w.write("\n"); w.flush()
            } catch (_: Exception) {
            }
        }
    }

    private fun answer(ctx: Context, req: JSONObject): JSONObject {
        val res = JSONObject().put("ok", true)
        when (req.optString("op")) {
            "info" -> {
                res.put("name", name(ctx))
                MainActivity.current?.let { a ->
                    // read the player state on the UI thread
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var st: JSONObject? = null
                    a.runOnUiThread { st = try { a.remoteStatus() } catch (_: Exception) { null }; latch.countDown() }
                    latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
                    st?.let { o -> o.keys().forEach { k -> res.put(k, o.get(k)) } }
                }
                res.put("playing", ctx.getSystemService(AudioManager::class.java).isMusicActive)
                res.put("battery", ctx.getSystemService(android.os.BatteryManager::class.java)
                    .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY))
            }
            "export" -> res.put("data", export(ctx))
            "cards" -> {
                val list = CardStore.load(ctx)
                res.put("cards", CardJson.toJson(list))
                res.put("covers", JSONArray(list.map { it.number }.filter { CoverStore.has(ctx, it) }))
            }
            "cover" -> CoverStore.bytes(ctx, req.optInt("nr"))?.let { res.put("img", Base64.encodeToString(it, Base64.NO_WRAP)) }
            "import" -> {
                importData(ctx, req.getJSONObject("data"))
                req.optJSONObject("covers")?.let { putCovers(ctx, it) }
                MainActivity.current?.let { a -> a.runOnUiThread { a.onRemoteImported() } }
            }
            "cmd" -> {
                val a = MainActivity.current ?: return res.put("ok", false).put("error", "app")
                val action = req.optString("action"); val nr = req.optInt("nr", -1)
                a.runOnUiThread { a.remoteCommand(action, nr) }
            }
            else -> res.put("ok", false).put("error", "op")
        }
        return res
    }

    // ------------------------------------------------------------------ data (both sides)

    /** All cards + settings of this phone (without the phone-specific keys). */
    fun export(ctx: Context): JSONObject {
        fun prefsJson(name: String, skip: Set<String> = emptySet()): JSONObject {
            val o = JSONObject()
            ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                if (k in skip || v == null) return@forEach
                val t = when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"; else -> return@forEach }
                o.put(k, JSONObject().put("t", t).put("v", v))
            }
            return o
        }
        return JSONObject()
            .put("cards", CardJson.toJson(CardStore.load(ctx)))
            .put("app", prefsJson("app", LOCAL_KEYS))
            .put("player", prefsJson("player"))
            .put("covers", JSONArray(CardStore.load(ctx).map { it.number }.filter { CoverStore.has(ctx, it) }))
    }

    /** Replaces cards and settings with the received ones. */
    fun importData(ctx: Context, d: JSONObject) {
        fun prefsFrom(name: String, o: JSONObject?, keep: Set<String> = emptySet()) {
            o ?: return
            val sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
            val e = sp.edit()
            sp.all.keys.filter { it !in keep }.forEach { e.remove(it) }
            o.keys().forEach { k ->
                if (k in keep) return@forEach
                val x = o.getJSONObject(k)
                when (x.getString("t")) {
                    "b" -> e.putBoolean(k, x.getBoolean("v"))
                    "i" -> e.putInt(k, x.getInt("v"))
                    "l" -> e.putLong(k, x.getLong("v"))
                    "f" -> e.putFloat(k, x.getDouble("v").toFloat())
                    "s" -> e.putString(k, x.getString("v"))
                }
            }
            e.apply()
        }
        d.optString("cards").takeIf { it.isNotBlank() }?.let { CardStore.importText(ctx, it, replace = true) }
        prefsFrom("app", d.optJSONObject("app"), LOCAL_KEYS)
        prefsFrom("player", d.optJSONObject("player"))
        Schedule.reschedule(ctx)
    }

    fun putCovers(ctx: Context, covers: JSONObject) {
        covers.keys().forEach { k ->
            val nr = k.toIntOrNull() ?: return@forEach
            try { CoverStore.putBytes(ctx, nr, Base64.decode(covers.getString(k), Base64.DEFAULT)) } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ client (the remote phone)

    /** A box this phone has connected to (saved; one of them is active). */
    data class Box(val name: String, val host: String, val pin: String)

    private fun c(ctx: Context) = ctx.getSharedPreferences("remote_client", Context.MODE_PRIVATE)

    fun known(ctx: Context): List<Box> = try {
        val a = JSONArray(c(ctx).getString("boxes", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { Box(it.optString("name"), it.getString("host"), it.getString("pin")) }
            .distinctBy { it.name.ifBlank { it.host } }   // older versions could save a box twice
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveKnown(ctx: Context, l: List<Box>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("name", it.name).put("host", it.host).put("pin", it.pin)) }
        c(ctx).edit().putString("boxes", a.toString()).apply()
    }

    /** Remember a box after a successful connection and make it the active one. */
    fun saveBox(ctx: Context, b: Box) {
        // same address or same box name (address changed in the Wi-Fi) -> replace the old entry
        saveKnown(ctx, listOf(b) + known(ctx).filter { it.host != b.host && it.name != b.name })
        c(ctx).edit().putString("active", b.host).apply()
    }

    fun removeBox(ctx: Context, host: String) {
        val name = known(ctx).firstOrNull { it.host == host }?.name
        val raw = try { JSONArray(c(ctx).getString("boxes", "[]")) } catch (e: Exception) { JSONArray() }
        val keep = (0 until raw.length()).map { raw.getJSONObject(it) }
            .filter { it.optString("host") != host && (name.isNullOrBlank() || it.optString("name") != name) }
            .map { Box(it.optString("name"), it.getString("host"), it.getString("pin")) }
        saveKnown(ctx, keep)
        if (c(ctx).getString("active", null) == host) { c(ctx).edit().remove("active").apply(); setRemoteMode(ctx, false) }
    }

    fun setActive(ctx: Context, host: String) = c(ctx).edit().putString("active", host).apply()

    fun active(ctx: Context): Box? = c(ctx).getString("active", null)?.let { h -> known(ctx).firstOrNull { it.host == h } }

    /** Remote mode: this phone controls the active box (cards in the list play on the box). */
    fun remoteMode(ctx: Context) = c(ctx).getBoolean("remoteMode", false) && active(ctx) != null

    fun setRemoteMode(ctx: Context, on: Boolean) = c(ctx).edit().putBoolean("remoteMode", on).apply()

    /** Fire-and-forget command to the active box; result on the main thread. */
    fun send(ctx: Context, action: String, nr: Int = -1, done: ((Boolean) -> Unit)? = null) {
        val b = active(ctx) ?: run { done?.invoke(false); return }
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        thread {
            val ok = try { call(b.host, b.pin, JSONObject().put("op", "cmd").put("action", action).put("nr", nr)).optBoolean("ok") } catch (e: Exception) { false }
            main.post { done?.invoke(ok) }
        }
    }

    /** Card list of the active box + numbers that have a picture. Call from a background thread. */
    fun fetchCards(ctx: Context): Pair<List<Card>, Set<Int>>? {
        val b = active(ctx) ?: return null
        return try {
            val r = call(b.host, b.pin, JSONObject().put("op", "cards"), 10_000)
            if (!r.optBoolean("ok")) return null
            val nrs = r.optJSONArray("covers")
            CardJson.parse(r.optString("cards", "{}")) to (0 until (nrs?.length() ?: 0)).map { nrs!!.getInt(it) }.toSet()
        } catch (e: Exception) {
            null
        }
    }

    /** Pictures of the box's cards, cached on this phone (per box). */
    object Covers {
        private val mem = HashMap<String, android.graphics.Bitmap?>()
        private fun file(ctx: Context, host: String, nr: Int) =
            java.io.File(java.io.File(ctx.cacheDir, "remote_covers").apply { mkdirs() }, "${host.replace(':', '_')}_$nr.jpg")

        @Synchronized
        fun get(ctx: Context, nr: Int): android.graphics.Bitmap? {
            val h = active(ctx)?.host ?: return null
            val k = "$h/$nr"
            if (mem.containsKey(k)) return mem[k]
            val f = file(ctx, h, nr)
            val b = if (f.exists()) android.graphics.BitmapFactory.decodeFile(f.absolutePath) else null
            if (b != null) mem[k] = b
            return b
        }

        /** Downloads missing pictures; returns true if any was added. Background thread. */
        fun fetchMissing(ctx: Context, nrs: Set<Int>): Boolean {
            val b = active(ctx) ?: return false
            var added = false
            for (nr in nrs) {
                val f = file(ctx, b.host, nr)
                if (f.exists()) continue
                try {
                    val img = call(b.host, b.pin, JSONObject().put("op", "cover").put("nr", nr), 10_000).optString("img")
                    if (img.isNotBlank()) { f.writeBytes(Base64.decode(img, Base64.DEFAULT)); added = true }
                } catch (_: Exception) {
                }
            }
            if (added) synchronized(this) { mem.clear() }
            return added
        }
    }

    /** Player state of the active box (null = not reachable). Call from a background thread. */
    fun fetchInfo(ctx: Context): JSONObject? {
        val b = active(ctx) ?: return null
        return try { call(b.host, b.pin, JSONObject().put("op", "info"), 4000).takeIf { it.optBoolean("ok") } } catch (e: Exception) { null }
    }

    /** Sends one request to a box. Throws on network errors; returns the JSON answer. */
    fun call(host: String, pin: String, req: JSONObject, timeoutMs: Int = 8000): JSONObject {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, PORT), 4000)
            s.soTimeout = timeoutMs
            val w = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
            w.write(req.put("pin", pin).toString()); w.write("\n"); w.flush()
            val line = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)).readLine()
                ?: throw java.io.IOException("no answer")
            return JSONObject(line)
        }
    }
}
