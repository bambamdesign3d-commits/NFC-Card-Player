package de.bambamdesign3d.nfccardplayer

import android.graphics.Color

/** Colour schemes for the small player bar. */
data class CardTheme(
    val nameDe: String,
    val nameEn: String,
    val background: Int,
    val text: Int,
    val subText: Int,
    val accent: Int,
    val track: Int
) {
    fun name() = t(nameDe, nameEn)

    companion object {
        val ALL = listOf(
            CardTheme("Standard (hell / blau)", "Standard (light / blue)",
                Color.rgb(0xF2, 0xF5, 0xFB), Color.rgb(0x1B, 0x1B, 0x1F), Color.rgb(0x44, 0x47, 0x4F),
                Color.rgb(0x3F, 0x6B, 0xD1), Color.rgb(0xD5, 0xDE, 0xF2)),
            CardTheme("Nacht (schwarz / rot)", "Night (black / red)",
                Color.BLACK, Color.WHITE, Color.rgb(0xB0, 0xB0, 0xB0),
                Color.rgb(0xE5, 0x39, 0x35), Color.rgb(0x45, 0x12, 0x12)),
            CardTheme("Ozean (dunkelblau / türkis)", "Ocean (dark blue / turquoise)",
                Color.rgb(0x0D, 0x1B, 0x2A), Color.rgb(0xE0, 0xF7, 0xFA), Color.rgb(0x90, 0xA4, 0xAE),
                Color.rgb(0x26, 0xC6, 0xDA), Color.rgb(0x16, 0x3A, 0x4A)),
            CardTheme("Wald (dunkelgrün / hellgrün)", "Forest (dark green / lime)",
                Color.rgb(0x1B, 0x2E, 0x1B), Color.rgb(0xF1, 0xF8, 0xE9), Color.rgb(0xA5, 0xD6, 0xA7),
                Color.rgb(0x9C, 0xCC, 0x65), Color.rgb(0x2E, 0x4A, 0x2E)),
            CardTheme("Sonne (creme / orange)", "Sun (cream / orange)",
                Color.rgb(0xFF, 0xF8, 0xE1), Color.rgb(0x3E, 0x27, 0x23), Color.rgb(0x6D, 0x4C, 0x41),
                Color.rgb(0xF5, 0x7C, 0x00), Color.rgb(0xFF, 0xE0, 0xB2))
        )

        fun get(i: Int) = ALL.getOrElse(i) { ALL[0] }
    }
}
