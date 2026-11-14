package io.github.corixien.viddaw.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

enum class Lang { En, De }

val LocalLang = staticCompositionLocalOf { Lang.En }

/** One UI string in every supported language. Effect and parameter names stay English, as in most DAWs. */
class Str(val en: String, val de: String) {
    fun of(lang: Lang) = if (lang == Lang.De) de else en
    val t: String
        @Composable @ReadOnlyComposable get() = of(LocalLang.current)
}

object T {
    val projects = Str("Projects", "Projekte")
    val newProject = Str("New project", "Neues Projekt")
    val noProjects = Str("No projects yet", "Noch keine Projekte")
    val noProjectsHint = Str("Create one to start recording.", "Lege eins an, um aufzunehmen.")
    val untitled = Str("Untitled", "Unbenannt")
    val rename = Str("Rename", "Umbenennen")
    val duplicate = Str("Duplicate", "Duplizieren")
    val delete = Str("Delete", "Löschen")
    val deleteProjectAsk = Str("Delete this project and its recordings?", "Dieses Projekt samt Aufnahmen löschen?")
    val cancel = Str("Cancel", "Abbrechen")
    val done = Str("Done", "Fertig")
    val name = Str("Name", "Name")
    val tracksCount = Str("tracks", "Spuren")
    val trackCountOne = Str("track", "Spur")
    val bluetoothMidi = Str("Bluetooth MIDI", "Bluetooth-MIDI")
    val midiHint = Str("USB MIDI devices connect on their own.", "USB-MIDI-Geräte verbinden sich von selbst.")
    val scan = Str("Search", "Suchen")
    val scanning = Str("Searching", "Suche läuft")
    val noDevices = Str("No devices found", "Keine Geräte gefunden")
    val connected = Str("Connected", "Verbunden")
    val connectFailed = Str("Could not connect", "Verbindung fehlgeschlagen")
    val settings = Str("Settings", "Einstellungen")
    val appearance = Str("Appearance", "Erscheinungsbild")
    val system = Str("System", "System")
    val light = Str("Light", "Hell")
    val dark = Str("Dark", "Dunkel")
    val language = Str("Language", "Sprache")
    val recordOffset = Str("Recording offset", "Aufnahme-Versatz")
    val recordOffsetHint = Str(
        "Shifts new recordings earlier. Raise it if your takes sound late.",
        "Verschiebt neue Aufnahmen nach vorn. Erhöhe den Wert, wenn Aufnahmen zu spät klingen.",
    )
    val about = Str("About", "Über")
    val aboutText = Str(
        "Viddaw is free software under the Apache 2.0 license.",
        "Viddaw ist freie Software unter der Apache-2.0-Lizenz.",
    )

    val addTrack = Str("Add track", "Spur hinzufügen")
    val audioTrack = Str("Audio", "Audio")
    val audioTrackHint = Str("Record or import sound", "Ton aufnehmen oder importieren")
    val samplerTrack = Str("Sampler", "Sampler")
    val samplerTrackHint = Str("Play one sample across the keys", "Ein Sample über die Tasten spielen")
    val drumTrack = Str("Drum rack", "Drum Rack")
    val drumTrackHint = Str("16 pads with a step grid", "16 Pads mit Step-Raster")
    val videoTrack = Str("Video", "Video")
    val videoTrackHint = Str("Clips with picture and sound", "Clips mit Bild und Ton")
    val emptyStudio = Str("Add a track to begin", "Füge eine Spur hinzu")
    val importAudio = Str("Import audio", "Audio importieren")
    val importVideo = Str("Import video", "Video importieren")
    val importing = Str("Importing", "Importiere")
    val importFailed = Str("This file could not be read.", "Diese Datei konnte nicht gelesen werden.")
    val micDenied = Str("Microphone access is needed to record.", "Zum Aufnehmen wird Mikrofonzugriff benötigt.")
    val recordFailed = Str("Recording could not start.", "Aufnahme konnte nicht starten.")
    val selectTrackToRecord = Str("Select an audio or instrument track to record.", "Wähle eine Audio- oder Instrumentenspur zum Aufnehmen.")

    val split = Str("Split", "Teilen")
    val edit = Str("Edit", "Bearbeiten")
    val newClip = Str("New clip", "Neuer Clip")
    val gain = Str("Gain", "Pegel")
    val track = Str("Track", "Spur")
    val volume = Str("Volume", "Lautstärke")
    val pan = Str("Pan", "Panorama")
    val mute = Str("Mute", "Stumm")
    val solo = Str("Solo", "Solo")
    val color = Str("Color", "Farbe")
    val moveUp = Str("Move up", "Nach oben")
    val moveDown = Str("Move down", "Nach unten")
    val deleteTrack = Str("Delete track", "Spur löschen")
    val mixer = Str("Mixer", "Mixer")
    val master = Str("Master", "Master")
    val effects = Str("Effects", "Effekte")
    val addEffect = Str("Add effect", "Effekt hinzufügen")
    val noEffects = Str("No effects on this track", "Keine Effekte auf dieser Spur")
    val instrument = Str("Instrument", "Instrument")
    val sample = Str("Sample", "Sample")
    val builtIn = Str("Built-in", "Eingebaut")
    val loadSample = Str("Load sample", "Sample laden")
    val rootNote = Str("Root note", "Grundton")
    val tune = Str("Tune", "Stimmung")
    val attack = Str("Attack", "Attack")
    val release = Str("Release", "Release")
    val pads = Str("Pads", "Pads")
    val keys = Str("Keys", "Tasten")
    val pad = Str("Pad", "Pad")
    val notes = Str("Notes", "Noten")
    val grid = Str("Grid", "Raster")
    val velocity = Str("Velocity", "Anschlag")
    val tempo = Str("Tempo", "Tempo")
    val timeSignature = Str("Time signature", "Taktart")
    val metronome = Str("Metronome", "Metronom")
    val loop = Str("Loop", "Loop")
    val snap = Str("Snap", "Raster")

    val export = Str("Export", "Exportieren")
    val format = Str("Format", "Format")
    val exporting = Str("Exporting", "Exportiere")
    val exportDone = Str("Saved to", "Gespeichert in")
    val exportFailed = Str("Export failed.", "Export fehlgeschlagen.")
    val exportEmpty = Str("There is nothing to export yet.", "Es gibt noch nichts zu exportieren.")
    val video = Str("Video", "Video")
}
