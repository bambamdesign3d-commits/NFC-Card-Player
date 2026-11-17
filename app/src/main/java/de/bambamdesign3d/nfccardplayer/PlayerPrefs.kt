package de.bambamdesign3d.nfccardplayer

import android.content.Context

/** Size and position of the player bar in millimetres (0.5 mm steps) and colour scheme. */
data class PlayerPrefs(
    val breiteMm: Float = 55f,
    val hoeheMm: Float = 20f,
    val linksMm: Float = 5f,
    val obenMm: Float = 3f,
    val imBoxModus: Boolean = true,
    val farbe: Int = 0
) {
    fun save(ctx: Context) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putFloat("breiteF", breiteMm)
            .putFloat("hoeheF", hoeheMm)
            .putFloat("linksF", linksMm)
            .putFloat("obenF", obenMm)
            .putBoolean("box", imBoxModus)
            .putInt("farbe", farbe)
            .apply()
    }

    companion object {
        private const val NAME = "player"

        fun load(ctx: Context): PlayerPrefs {
            val p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            val d = PlayerPrefs()
            fun f(newKey: String, oldKey: String, def: Float): Float =
                if (p.contains(newKey)) p.getFloat(newKey, def)
                else try { p.getInt(oldKey, def.toInt()).toFloat() } catch (e: Exception) { def }
            return PlayerPrefs(
                f("breiteF", "breite", d.breiteMm),
                f("hoeheF", "hoehe", d.hoeheMm),
                f("linksF", "links", d.linksMm),
                f("obenF", "oben", d.obenMm),
                p.getBoolean("box", d.imBoxModus),
                p.getInt("farbe", d.farbe)
            )
        }
    }
}
