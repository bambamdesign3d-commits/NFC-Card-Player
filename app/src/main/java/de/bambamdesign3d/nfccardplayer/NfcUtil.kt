package de.bambamdesign3d.nfccardplayer

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable

object NfcUtil {
    const val MIME = "application/vnd.de.bambamdesign3d.nfccardplayer"

    /** Kartennummer aus einer NDEF-Nachricht holen (eigenes Format, Text-Eintrag oder reine Zahl). */
    fun parseNumber(msg: NdefMessage?): Int? {
        msg ?: return null
        for (r in msg.records) {
            val text = when {
                r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_TEXT) -> {
                    val p = r.payload
                    if (p.isEmpty()) continue
                    val langLen = p[0].toInt() and 0x3F
                    if (p.size <= 1 + langLen) continue
                    String(p, 1 + langLen, p.size - 1 - langLen, Charsets.UTF_8)
                }
                r.tnf == NdefRecord.TNF_MIME_MEDIA ||
                    r.tnf == NdefRecord.TNF_EXTERNAL_TYPE ||
                    r.tnf == NdefRecord.TNF_UNKNOWN -> String(r.payload, Charsets.UTF_8)
                else -> continue
            }
            val nr = text.trim().toIntOrNull()
            if (nr != null) return nr
        }
        return null
    }

    fun readNumber(tag: Tag): Int? {
        val ndef = Ndef.get(tag) ?: return null
        ndef.cachedNdefMessage?.let { cached -> parseNumber(cached)?.let { return it } }
        return try {
            ndef.connect()
            parseNumber(ndef.ndefMessage)
        } catch (e: Exception) {
            null
        } finally {
            try { ndef.close() } catch (_: Exception) {}
        }
    }

    fun idHex(tag: Tag): String = tag.id.joinToString("") { "%02X".format(it) }

    /** Schreibt die Kartennummer auf die Karte. Gibt null bei Erfolg zurück, sonst eine Fehlermeldung. */
    fun write(tag: Tag, nummer: Int, packageName: String): String? {
        val msg = NdefMessage(
            arrayOf(
                NdefRecord.createMime(MIME, nummer.toString().toByteArray(Charsets.UTF_8)),
                NdefRecord.createApplicationRecord(packageName)
            )
        )
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            return try {
                ndef.connect()
                when {
                    !ndef.isWritable -> t("Karte ist schreibgeschützt", "Card is write-protected")
                    ndef.maxSize < msg.byteArrayLength -> t("Karte zu klein (${ndef.maxSize} Byte)", "Card too small (${ndef.maxSize} bytes)")
                    else -> {
                        ndef.writeNdefMessage(msg)
                        null
                    }
                }
            } catch (e: Exception) {
                t("Schreiben fehlgeschlagen – Karte ruhig halten und nochmal versuchen", "Writing failed – hold the card still and try again")
            } finally {
                try { ndef.close() } catch (_: Exception) {}
            }
        }
        val formatable = NdefFormatable.get(tag) ?: return t("Kartentyp wird nicht unterstützt", "Card type not supported")
        return try {
            formatable.connect()
            formatable.format(msg)
            null
        } catch (e: Exception) {
            t("Formatieren fehlgeschlagen", "Formatting failed")
        } finally {
            try { formatable.close() } catch (_: Exception) {}
        }
    }
}
