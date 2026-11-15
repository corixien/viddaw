# Viddaw: project resume

Read this first, then `AGENTS.md` (commands and invariants) and `README.md` (overview).

## Goal
DAW-style audio and video editor for Android, later web, Windows and Linux. Local only: no
accounts, cloud, ads or tracking. Not sold; must be legally clean. Apache-2.0, public repo
`corixien/viddaw`, package `io.github.corixien.viddaw`, minSdk 28, compileSdk 37, targetSdk 36.

## Decisions (confirmed by the user)
- Stack: Kotlin + Compose Multiplatform UI in `shared/` (targets android + jvm), portable C++20
  engine in `engine/core`, Oboe + JNI in `engine/android`, Android app in `androidApp/`.
- Versions: Gradle 9.8.1, AGP 9.4.1, Kotlin 2.4.21, Compose MP 1.12.2, Oboe 1.11.0,
  Media3 1.11.1, Lucide icons 2.2.1 (`gradle/libs.versions.toml`).
- Only permissive dependencies. No Rubber Band, JUCE, Apple fonts or symbols. No code, assets,
  names or loops from the analysed app. Drum kit and sampler tone are synthesised in `instruments.cpp`.
- Design: flat, no gradients, macOS feel, graphite accent, colour only from tracks and clips,
  Inter font, Lucide icons, animations on every user action. Own components (`ui/Kit.kt`), no Material widgets.
- German + English through the `Str(en, de)` table in `ui/Strings.kt`; follows the system, overridable.
  Theme light, dark or system.
- No instruments beyond a simple sampler and a drum rack, both with MIDI.
- User wants work pushed to `main` directly and all `.md` files kept current.

## Architecture
- Timeline positions in ticks (PPQ 960); audio lengths in frames at 48 kHz. Kotlin does all tempo maths.
- Project is immutable data (`model/Model.kt`); edits are pure functions (`model/Edits.kt`); undo is a list of projects.
- `StudioState` (`app/StudioState.kt`) owns the open project: undo, selection, transport, recording, import, export.
  `AppState` owns settings and the project library. Sheets are state (`Panel`), so system back can close them.
- The engine gets the whole project as a text snapshot (`engineSnapshot`, format in `engine/core/engine.h`),
  swapped in with a try-lock on the audio thread. DSP state survives by track and effect id.
- All project audio is float32 WAV, memory-mapped. Imports are decoded with MediaCodec and resampled by `Importer`.
- Video: clips on Video tracks carry the source URI plus the extracted audio as a normal WAV. The engine plays
  the sound; a muted ExoPlayer chases the engine position for preview. Export bounces the mix to WAV, then
  Media3 Transformer renders clips and gaps (black) over it.
- Host services go through `app/Platform.kt`; Android implementation in `androidApp/.../AndroidPlatform.kt`,
  `MidiInput.kt`, `VideoSupport.kt`.
- Projects live in `<filesDir>/projects/<id>/project.json` with `audio/*.wav` beside it.

## Current state (2026-11-15)
Version 0.1.0. Everything below is built and was exercised on a Galaxy S23+ (Android 17) over adb.

Working and verified on device:
- Project list, create, settings (theme, language), light and dark, portrait and landscape.
- Audio recording to a clip with live waveform; audio import (MP3 44.1 kHz resampled, frequency checked).
- Timeline: select, drag-move with snap, split, duplicate, delete, undo, pinch zoom, follow playhead.
- Drum rack with step editor, sampler with on-screen keys, MIDI note recording, piano roll.
- Mixer, track settings, effect chain UI with all 13 effects.
- Export WAV 24 bit, FLAC, M4A: files valid, drum hits sample-exact on the grid, reverb tail present.
- Video import, synced preview, MP4 export (H.264 + AAC), gap before a clip exports as black.

Verified by tests only:
- Engine core: `engine/tests/tests.cpp` (files, importer, every effect, mix, instruments, transport, recording).
- Model: 12 tests in `shared/src/commonTest` (edits, snapshot, parameter mapping, serialization).

Not verified:
- Bluetooth MIDI scan and connect, and USB MIDI input: no MIDI device was available.
- Acoustic round-trip latency: the phone was on Bluetooth headphones, so the speaker-to-mic test was not
  possible. Recording alignment uses Oboe's latency estimate (about 270 ms on Bluetooth) plus the
  "Recording offset" setting.
- Trim handles and loop handles by touch (covered by model tests, not driven on the device).
- Tablets and API levels below 37.

Release:
- Signed with `~/.config/viddaw/viddaw-release.jks`, passwords in `~/.config/viddaw/keystore.properties`.
  Both are outside the repo and must be backed up; losing them means updates cannot install over old versions.
- The phone has the debug build installed. The release APK has a different signature and will not install over it.

## Known gaps and next steps
1. Landscape on phones shows only two tracks: merge the context bar into the transport bar in wide layouts.
2. Time-stretch and pitch-shift for audio clips (Signalsmith Stretch, MIT, is the candidate library).
3. Clip gain and fades in the UI (the model already has `gainDb`).
4. Tempo-synced delay, automation, input monitoring, master effects.
5. Overlapping clips on one track both play; decide whether the later clip should cut the earlier one.
6. Piano roll: velocity editing; white keys are not drawn in the key column.
7. Video: several video tracks composite as "first clip wins"; no transitions or titles yet.
8. Desktop and web targets: add a JVM desktop app and a miniaudio or WASM audio backend behind `AudioEngine`.
9. Trim project audio that no clip references any more (files of deleted clips stay on disk for undo).

## Working notes
- A shell hook in this environment redirects commands containing build-tool names or curl to the
  context-mode `ctx_execute` tool.
- After `adb install`, wait two seconds before `am start`; otherwise Android kills the fresh process.
- The phone reports density 2.75; screenshots scaled to 36 % map to device pixels by dividing by 0.36.
- The host ffmpeg cannot decode the phone's H.264; use GStreamer `vah264dec` to check exported frames.
