package de.bambamdesign3d.nfccardplayer

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import java.util.Calendar

/** App-Einstellungen für Autostart und Display-Zeitschaltung. */
object AppPrefs {
    private const val NAME = "app"
    private fun p(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun autostart(ctx: Context) = p(ctx).getBoolean("autostart", true)
    fun language(ctx: Context): String =
        p(ctx).getString("lang", null) ?: "en"
    fun setLanguage(ctx: Context, lang: String) = p(ctx).edit().putString("lang", lang).apply()
    fun overLock(ctx: Context) = p(ctx).getBoolean("overLock", true)
    fun startBox(ctx: Context) = p(ctx).getBoolean("startBox", true)
    /** true if at least one display period is active. */
    fun schedule(ctx: Context) = (0 until Period.COUNT).any { Period.load(ctx, it).enabled }
    fun autoBox(ctx: Context) = p(ctx).getBoolean("autoBox", true)
    /** Box mode: switch the display off completely after 30 s without use (needs device admin). */
    fun displayOff(ctx: Context) = p(ctx).getBoolean("displayOff", false)
    /** "Display off = pause": music pauses when the display is switched off (power button or schedule). */
    fun offPause(ctx: Context) = p(ctx).getBoolean("offPause", false)
    /** Box mode brightness after tapping (percent). */
    fun boxBrightness(ctx: Context) = p(ctx).getInt("boxBrightness", 60)
    /** Maximum media volume in percent (100 = no limit). */
    fun volumeLimit(ctx: Context) = p(ctx).getInt("volumeLimit", 70)

    fun set(ctx: Context, key: String, v: Boolean) = p(ctx).edit().putBoolean(key, v).apply()
    fun set(ctx: Context, key: String, v: Int) = p(ctx).edit().putInt(key, v).apply()

    fun fmt(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    internal fun prefs(ctx: Context) = p(ctx)
}

/**
 * One display period: display off at [off], on again at [on] (may cross midnight),
 * only on the selected weekdays (bit 0 = Monday … bit 6 = Sunday; day = day the period starts).
 */
data class Period(val index: Int, val enabled: Boolean, val off: Int, val on: Int, val days: Int) {
    fun hasDay(dow: Int) = days and (1 shl dow) != 0

    fun save(ctx: Context) {
        AppPrefs.prefs(ctx).edit()
            .putBoolean("p${index}_en", enabled).putInt("p${index}_off", off)
            .putInt("p${index}_on", on).putInt("p${index}_days", days).apply()
    }

    companion object {
        const val COUNT = 3
        const val ALL_DAYS = 0b1111111
        private val DEFAULTS = listOf(20 * 60 to 7 * 60, 12 * 60 + 30 to 14 * 60, 9 * 60 to 12 * 60)

        fun load(ctx: Context, i: Int): Period {
            val p = AppPrefs.prefs(ctx)
            migrate(ctx)
            val (dOff, dOn) = DEFAULTS[i]
            return Period(i, p.getBoolean("p${i}_en", false), p.getInt("p${i}_off", dOff),
                p.getInt("p${i}_on", dOn), p.getInt("p${i}_days", ALL_DAYS))
        }

        fun all(ctx: Context) = (0 until COUNT).map { load(ctx, it) }

        /** Takes over the two periods of older versions once. */
        private fun migrate(ctx: Context) {
            val p = AppPrefs.prefs(ctx)
            if (p.getBoolean("periods_migrated", false)) return
            val e = p.edit().putBoolean("periods_migrated", true)
            if (p.contains("schedule")) {
                val on = p.getBoolean("schedule", false)
                e.putBoolean("p0_en", on).putInt("p0_off", p.getInt("offMin", 20 * 60)).putInt("p0_on", p.getInt("onMin", 7 * 60))
                e.putBoolean("p1_en", on && p.getBoolean("schedule2", false))
                    .putInt("p1_off", p.getInt("offMin2", 12 * 60 + 30)).putInt("p1_on", p.getInt("onMin2", 14 * 60))
            }
            e.apply()
        }

        /** Monday = 0 … Sunday = 6 */
        fun dow(c: Calendar) = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
    }
}

/** Geräteadministrator – nur nötig, damit die App das Display sofort ausschalten darf. */
class AdminReceiver : DeviceAdminReceiver()

object Schedule {
    const val ACTION_ON = "de.bambamdesign3d.nfccardplayer.DISPLAY_ON"
    const val ACTION_OFF = "de.bambamdesign3d.nfccardplayer.DISPLAY_OFF"

    fun adminComponent(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)

    fun isAdmin(ctx: Context): Boolean =
        (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(adminComponent(ctx))

    /** Display sofort aus (nur mit Geräteadministrator möglich). */
    fun lockNow(ctx: Context): Boolean = try {
        if (isAdmin(ctx)) {
            (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).lockNow()
            true
        } else false
    } catch (e: Exception) {
        false
    }

    fun canExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    }

    /** Is the display supposed to be off at [c] (default: now)? */
    fun isOffTime(ctx: Context, c: Calendar = Calendar.getInstance()): Boolean {
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val today = Period.dow(c)
        val yesterday = (today + 6) % 7
        return Period.all(ctx).any { p ->
            when {
                !p.enabled || p.off == p.on -> false
                p.off < p.on -> p.hasDay(today) && now >= p.off && now < p.on
                else -> (p.hasDay(today) && now >= p.off) || (p.hasDay(yesterday) && now < p.on)
            }
        }
    }

    /** Was the display off a few minutes ago? (used when an "on" alarm fires) */
    fun wasOffBefore(ctx: Context): Boolean =
        isOffTime(ctx, Calendar.getInstance().apply { add(Calendar.MINUTE, -3) })

    private fun nextAt(min: Int): Long {
        val c = Calendar.getInstance().apply {
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, min / 60); set(Calendar.MINUTE, min % 60)
        }
        if (c.timeInMillis <= System.currentTimeMillis() + 1000) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun pi(ctx: Context, action: String, code: Int) = PendingIntent.getBroadcast(
        ctx, code,
        Intent(ctx, ScheduleReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Set all alarms (display on / off) for the active periods; receivers check the weekdays. */
    fun reschedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        fun set(t: Long, p: PendingIntent) {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, p)
        }
        for (p in Period.all(ctx)) {
            val on = pi(ctx, ACTION_ON, 11 + p.index * 2)
            val off = pi(ctx, ACTION_OFF, 12 + p.index * 2)
            am.cancel(on); am.cancel(off)
            if (!p.enabled || p.days == 0) continue
            set(nextAt(p.on), on)
            set(nextAt(p.off), off)
        }
    }

    /** App öffnen (über dem Sperrbildschirm, Display an) und Box-Modus starten. */
    fun openApp(ctx: Context, wake: Boolean) {
        if (wake) {
            try {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("DEPRECATION")
                pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "cardmusik:wake"
                ).acquire(10_000)
            } catch (_: Exception) {
            }
        }
        try {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(MainActivity.EXTRA_WAKE, true)
            )
        } catch (_: Exception) {
        }
    }
}

/** Wird zu den eingestellten Zeiten ausgelöst. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Schedule.ACTION_ON -> if (!Schedule.isOffTime(ctx) && Schedule.wasOffBefore(ctx)) Schedule.openApp(ctx, wake = true)
            Schedule.ACTION_OFF -> {
                val stop = AppPrefs.offPause(ctx)
                val a = MainActivity.current
                if (a != null) a.runOnUiThread { a.goDark(stop) }
                else {
                    if (stop) StreamStarter.key(ctx, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
                    Schedule.lockNow(ctx)
                }
            }
        }
        Schedule.reschedule(ctx)
    }
}

/** Startet die App nach dem Einschalten des Handys. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Schedule.reschedule(ctx)
        if (!AppPrefs.autostart(ctx)) return
        if (Schedule.isOffTime(ctx)) return // nachts nicht einschalten – der Wecker weckt morgens
        Schedule.openApp(ctx, wake = true)
    }
}
