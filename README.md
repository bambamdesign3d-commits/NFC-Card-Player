# NFC Card Player

**English** · [Deutsch](#deutsch)

An Android app that turns a phone and a Bluetooth speaker into a music box for kids. Hold an NFC card to the phone and the matching music starts: an album, a radio play, an audiobook, or an Amazon Music / Spotify playlist. This app is optimized for the NFC Music Station Project: https://makerworld.com/models/3654709

## Contents

1. [Downloads](#downloads)
2. [Installation](#installation)
3. [Setting up cards](#setting-up-cards)
4. [Features](#features)
5. [Remote control & sync (2nd phone)](#remote-control--sync-2nd-phone)
6. [Import / export (cards.json)](#import--export-cardsjson)
7. [Permissions & security](#permissions--security)
8. [Licenses](#licenses)
9. [Building](#building)
10. [Changelog](CHANGELOG.md)

## Downloads

The latest version is under **Releases**:

| File | Description |
|---|---|
| `NFC-Card-Player.apk` | The app – installs normally. |
| `cards.json` | Optional example cards for the import. |

## Installation

1. Download the APK on the phone and open it (allow “install unknown apps”).
2. Open the app → **Settings** → grant the permissions you need. Every switch is only available when its permission is granted; **?** explains what it is for.
3. Optional: put `cards.json` into the `Download` folder – it is imported on the first start.

## Setting up cards

1. **+ Card** creates a new card (numbers 1–999, the next free number is suggested).
2. Choose the source: a folder on the phone, Amazon Music or Spotify.
   Tip: in Amazon Music or Spotify tap *Share* on a playlist and choose **NFC Card Player** – link, name and preview picture are taken directly from there.
3. **Write card** and hold an NFC card (NTAG213/215) to the phone.

Written cards also start the app when it is closed (display on and unlocked).

## Features

- **Modes:** in order, shuffle, single track, one random track, sleep timer, freeze dance
- **Resume:** remembers track and position (restarts 10 s earlier) – ideal for audiobooks
- **Streaming:** Amazon Music and Spotify playlists start directly, the app returns to the front automatically
- **Player bar:** picture, title, progress, battery; size, position and colour scheme adjustable
- **Box mode:** black screen at minimum brightness, NFC stays active; tap = brighter, tap again outside the player bar = exit; switches on by itself after 30 s without use
- **Display schedule:** up to 3 periods with weekdays, autostart after reboot
- **Brightness & volume:** adjustable box mode brightness, maximum volume (default 70 %, also for Bluetooth speakers)
- **Volume key held for 2 s:** pause (+ or −; the volume returns to its previous value; while the app is shown)
- **Options:** display completely off after 30 s, “display off = pause” (power button or schedule pauses the music)
- **Remote control & sync:** a second phone in the same Wi-Fi can control the box (play/pause, skip, start a card, volume) and copy all cards, pictures and settings in either direction (box PIN); in **remote mode** the cards in its list play directly on the box and the player bar shows the box
- **Voice announcements** for sleep timer and freeze dance, German / English
- **Preset cards:** 996 test music, 997 freeze dance, 998 timer 30 min, 999 timer 60 min

## Remote control & sync (2nd phone)

A second phone with NFC Card Player can control the box and copy data – directly over the home Wi-Fi, no internet, no account, no extra permissions.

1. **On the box:** menu → *Remote control & sync (2nd phone)* → switch on *This is a remote controlled device (box)*. Name, address and a 4-digit PIN are shown.
2. **On the 2nd phone** (same Wi-Fi): open the same menu – the box is found automatically. Tap it and enter the PIN. Connected boxes are saved (several possible, one is active ✓; 🗑 removes one).
3. **Remote mode:** the main screen now has a *Remote mode* button. In remote mode
   - the card list of the box is shown (with pictures) and tapping a card plays it **on the box** – NFC cards held to the 2nd phone are passed on too,
   - the player bar shows exactly what the box shows (title, progress, timer, freeze dance, battery),
   - ⏮ ⏯ ⏭ and *− Vol / + Vol* control the box,
   - the connection status (🟢 connected / 🔴 not reachable) opens the connection menu when tapped.
4. **Transfer:** copy *all cards*, *card pictures*, *settings* and *player bar* (each selectable) from the box to the phone or the other way round. Never transferred: permissions, saved listening positions, remote settings. Folder cards need the MP3s on both phones in the same folder.

While sharing is on, the box keeps its Wi-Fi awake so the connection does not drop when the display is dark.

## Import / export (cards.json)

All cards are stored inside the app. **Settings menu → Export** saves them to `Download/cards_export.json`; **Import** reads a JSON file.

```json
{
  "1":   { "name": "Album", "mode": "row", "folder": "/storage/emulated/0/Music/01", "statesave": "no" },
  "4":   { "name": "Audiobook", "mode": "row", "folder": "/storage/emulated/0/Music/04", "statesave": "yes" },
  "5":   { "name": "Song", "mode": "single", "folder": "/storage/emulated/0/Music/05", "file": "001 Song.mp3" },
  "6":   { "name": "Playlist", "source": "amazon", "link": "https://music.amazon.de/playlists/…" },
  "7":   { "name": "Playlist", "source": "spotify", "link": "https://open.spotify.com/playlist/…" },
  "997": { "name": "Freeze dance", "mode": "freezedance", "playMin": 7, "playMax": 30, "pauseMin": 3, "pauseMax": 8 },
  "998": { "name": "Sleep timer", "mode": "timer", "minutes": 30, "fade": 30 }
}
```

| mode | Behaviour |
|---|---|
| `row` | folder in order |
| `random` | folder shuffled, keeps playing |
| `single` | one specific file (`file`) |
| `singlerandom` | one random file, then stop |
| `timer` | sleep timer (`minutes`, `fade` in s); scan again = off |
| `freezedance` | music `playMin`–`playMax` s, pause `pauseMin`–`pauseMax` s; scan again = off |

Further keys: `statesave` (`yes`/`no`), `source` (`amazon`/`spotify`), `link`.

## Permissions & security

All permissions are optional and explained in the app (storage, display over other apps, battery optimisation, device admin for turning the display off). The app does not need accessibility or notification access. It sends data only when creating a streaming card (to Amazon Music / Spotify) or searching for a picture online (iTunes search).

## Licenses

Third-party components (AndroidX, Media3, Material Components, Guava, Kotlin, Material Icons) are used under the Apache License 2.0 – see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt). Amazon Music and Spotify are trademarks of their owners; this project is not affiliated with them.

## Building

GitHub Actions builds the APK on every push to `main` and publishes them as release `V<number>` (from the `VERSION` file). All builds are signed with the same private key, stored only as repository secrets (`KEYSTORE_B64`, `KEYSTORE_PASSWORD`) – only the owner can publish updates that install over the app.

---

<a name="deutsch"></a>
# NFC Card Player (Deutsch)

[English](#nfc-card-player) · **Deutsch**

Eine Android-App, die aus Handy und Bluetooth-Lautsprecher eine Musikbox für Kinder macht. NFC-Karte ans Handy halten – und die passende Musik startet: Album, Hörspiel, Hörbuch oder eine Playlist aus Amazon Music / Spotify. Die App ist für das Projekt NFC Music Station optimiert: https://makerworld.com/models/3654709

## Inhalt

1. [Downloads](#downloads-1)
2. [Installation](#installation-1)
3. [Karten einrichten](#karten-einrichten)
4. [Funktionen](#funktionen)
5. [Fernbedienung & Sync (2. Handy)](#fernbedienung--sync-2-handy)
6. [Import / Export (cards.json)](#import--export-cardsjson-1)
7. [Berechtigungen & Sicherheit](#berechtigungen--sicherheit)
8. [Lizenzen](#lizenzen)
9. [Bauen](#bauen)
10. [Changelog](CHANGELOG.md)

## Downloads

Die aktuelle Version liegt unter **Releases**:

| Datei | Beschreibung |
|---|---|
| `NFC-Card-Player.apk` | Die App – installiert sich normal. |
| `cards.json` | Optionale Beispielkarten für den Import. |

## Installation

1. APK auf dem Handy herunterladen und öffnen („Unbekannte Apps installieren“ erlauben).
2. App öffnen → **Einstellungen** → benötigte Berechtigungen erteilen. Jeder Schalter ist nur mit der passenden Berechtigung bedienbar; **?** erklärt, wofür sie gebraucht wird.
3. Optional: `cards.json` in den Ordner `Download` legen – wird beim ersten Start übernommen.

## Karten einrichten

1. **+ Karte** legt eine neue Karte an (Nummern 1–999, die nächste freie Nummer wird vorgeschlagen).
2. Quelle wählen: Ordner auf dem Handy, Amazon Music oder Spotify.
   Tipp: In Amazon Music oder Spotify bei einer Playlist auf *Teilen* tippen und **NFC Card Player** wählen – Link, Name und Vorschaubild werden direkt von dort übernommen.
3. **Karte schreiben** und eine NFC-Karte (NTAG213/215) auflegen.

Beschriebene Karten starten die App auch, wenn sie geschlossen ist (Display an und entsperrt).

## Funktionen

- **Abspielarten:** der Reihe nach, zufällig, einzelner Titel, ein zufälliger Titel, Schlaf-Timer, Stoptanz
- **Weiterhören:** merkt sich Titel und Stelle (startet 10 s vorher) – ideal für Hörbücher
- **Streaming:** Playlists aus Amazon Music und Spotify starten direkt, die App kommt automatisch wieder nach vorne
- **Player-Leiste:** Bild, Titel, Fortschritt, Akku; Größe, Position und Farbschema einstellbar
- **Box-Modus:** schwarzer Bildschirm mit minimaler Helligkeit, NFC bleibt aktiv; tippen = heller, nochmal tippen außerhalb der Player-Leiste = beenden; schaltet sich nach 30 s ohne Bedienung selbst ein
- **Display-Zeitschaltung:** bis zu 3 Zeiträume mit Wochentagen, Autostart nach Neustart
- **Helligkeit & Lautstärke:** Helligkeit im Box-Modus einstellbar, maximale Lautstärke (Standard 70 %, auch für Bluetooth-Lautsprecher)
- **Lautstärketaste 2 s halten:** Pause (+ oder −; die Lautstärke springt auf den vorherigen Wert zurück; solange die App angezeigt wird)
- **Optionen:** Display nach 30 s ganz aus, „Display aus = Pause“ (An/Aus-Schalter oder Zeitschaltung pausiert die Musik)
- **Fernbedienung & Sync:** ein zweites Handy im selben WLAN kann die Box steuern (Play/Pause, Titel, Karte starten, Lautstärke) und alle Karten, Bilder und Einstellungen in beide Richtungen übertragen (mit PIN der Box); im **Remote-Modus** spielen die Karten seiner Liste direkt auf der Box, die Player-Leiste zeigt die Box
- **Sprachansagen** für Schlaf-Timer und Stoptanz, Deutsch / Englisch
- **Vorbelegte Karten:** 996 Testmusik, 997 Stoptanz, 998 Timer 30 min, 999 Timer 60 min

## Fernbedienung & Sync (2. Handy)

Ein zweites Handy mit NFC Card Player kann die Box steuern und Daten übertragen – direkt über das heimische WLAN, ohne Internet, ohne Konto, ohne zusätzliche Berechtigungen.

1. **Auf der Box:** Menü → *Fernbedienung & Sync (2. Handy)* → *Dies ist ein ferngesteuertes Gerät (Box)* einschalten. Name, Adresse und eine 4-stellige PIN werden angezeigt.
2. **Auf dem 2. Handy** (gleiches WLAN): denselben Menüpunkt öffnen – die Box wird automatisch gefunden. Antippen und PIN eingeben. Verbundene Boxen werden gespeichert (mehrere möglich, eine ist aktiv ✓; 🗑 entfernt eine).
3. **Remote-Modus:** Im Hauptbildschirm erscheint der Knopf *Remote-Modus*. Im Remote-Modus
   - wird die Kartenliste der Box angezeigt (mit Bildern); eine angetippte Karte spielt **auf der Box** – auch NFC-Karten am 2. Handy werden weitergegeben,
   - zeigt die Player-Leiste genau das, was die Box zeigt (Titel, Fortschritt, Timer, Stoptanz, Akku),
   - steuern ⏮ ⏯ ⏭ und *− Vol / + Vol* die Box,
   - öffnet die Verbindungsanzeige (🟢 verbunden / 🔴 nicht erreichbar) beim Antippen das Verbindungsmenü.
4. **Übertragen:** *Alle Karten*, *Kartenbilder*, *Einstellungen* und *Player-Leiste* (einzeln wählbar) von der Box aufs Handy holen oder umgekehrt senden. Nie übertragen: Berechtigungen, gespeicherte Hörstellen, Fernbedienungs-Einstellungen. Ordner-Karten brauchen die MP3s auf beiden Handys im gleichen Ordner.

Solange die Freigabe an ist, hält die Box ihr WLAN wach, damit die Verbindung bei dunklem Display nicht abbricht.

## Import / Export (cards.json)

Alle Karten werden in der App gespeichert. **Einstellungsmenü → Export** speichert sie nach `Download/cards_export.json`; **Import** liest eine JSON-Datei ein. Aufbau und Felder siehe [englischer Teil](#import--export-cardsjson).

| mode | Verhalten |
|---|---|
| `row` | Ordner der Reihe nach |
| `random` | Ordner zufällig, spielt weiter |
| `single` | eine bestimmte Datei (`file`) |
| `singlerandom` | eine zufällige Datei, dann Stopp |
| `timer` | Schlaf-Timer (`minutes`, `fade` in s); erneut auflegen = aus |
| `freezedance` | Musik `playMin`–`playMax` s, Pause `pauseMin`–`pauseMax` s; erneut auflegen = aus |

Weitere Felder: `statesave` (`yes`/`no`), `source` (`amazon`/`spotify`), `link`.

## Berechtigungen & Sicherheit

Alle Berechtigungen sind optional und in der App erklärt (Speicher, Über anderen Apps einblenden, Akku-Optimierung, Geräteadministrator zum Ausschalten des Displays). Bedienungshilfe oder Benachrichtigungszugriff braucht die App nicht. Daten sendet sie nur beim Anlegen einer Streaming-Karte (an Amazon Music / Spotify) und bei der Online-Bildsuche (iTunes-Suche).

## Lizenzen

Drittanbieter-Komponenten (AndroidX, Media3, Material Components, Guava, Kotlin, Material Icons) stehen unter der Apache License 2.0 – siehe [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) und [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt). Amazon Music und Spotify sind Marken ihrer Inhaber; dieses Projekt steht in keiner Verbindung zu ihnen.

## Bauen

GitHub Actions baut bei jedem Push auf `main` die APK und veröffentlicht sie als Release `V<Nummer>` (aus der Datei `VERSION`). Alle Builds sind mit demselben privaten Schlüssel signiert, der nur als Repository-Secret hinterlegt ist (`KEYSTORE_B64`, `KEYSTORE_PASSWORD`) – nur der Besitzer kann Updates veröffentlichen, die sich über die App installieren lassen.

---

© BamBam Design · [www.bambam-design.com](https://www.bambam-design.com)
