# Viddaw: project resume

## Goal
DAW-style audio and video editor for Android (later web, Windows, Linux). Local only: no accounts, cloud, ads or tracking. Step 1 matches the feature set of a mobile DAW studio; video tracks follow. Apache-2.0, public repo `corixien/viddaw`, package `io.github.corixien.viddaw`, minSdk 28.

## Decisions (confirmed by user)
- Stack: Kotlin + Compose Multiplatform UI (`shared/`, targets android + jvm), portable C++20 engine (`engine/core`), Oboe + JNI on Android (`engine/android`), Android app in `androidApp/`.
- Versions: Gradle 9.8.1, AGP 9.4.1, Kotlin 2.4.21, Compose MP 1.12.2, Oboe 1.11.0, Media3 1.11.1 (see `gradle/libs.versions.toml`).
- Only permissive dependencies. No Rubber Band, JUCE, SF fonts/symbols, no BandLab code, assets or names. Drum kit and sampler tone are synthesised in `instruments.cpp`.
- Design: flat, no gradients, macOS feel, graphite accent, colour only from tracks/clips, Inter font, Lucide icons, animations everywhere. No Material look: own components on Compose foundation.
- i18n: German + English via a `Str(en, de)` table in Kotlin, follows system, overridable. Theme light/dark/system.
- MVP: audio recording, import, timeline (move/trim/split), loop, metronome, undo/redo, mixer, 13 effects, sampler + drum rack with MIDI clips, piano roll, pads, USB/BT MIDI, export WAV/FLAC/M4A. Then video tracks (Media3 preview + Transformer export).
- User wants M0 to M5 plus video built in one go, tested on the attached phone (Galaxy S23+, Android 17, adb) before each next step. Stop only for questions.

## Architecture
- Timeline positions in ticks (PPQ 960), audio lengths in frames at 48 kHz. Kotlin does all tempo maths.
- Project = immutable data classes (`model/Model.kt`), edits are pure functions (`model/Edits.kt`), undo = list of projects.
- Engine receives the whole project as a text snapshot (`engineSnapshot` in `engine/AudioEngine.kt`, format in `engine/core/engine.h`), swapped in lock-free; DSP state survives by track/effect id.
- All project audio is float32 WAV, memory-mapped. Imports are decoded on the platform side and resampled by `Importer`.
- Effect parameter order: `model/Effects.kt` must match `engine/core/effects.cpp`.
- Host OS access goes through `app/Platform.kt` (files, pickers, import, export, MIDI, video preview).

## Current state
Done:
- Repo created (public, empty remote), build files written, not yet built with Gradle (no wrapper generated yet).
- Engine core complete and passing host tests: `g++ -std=c++20 -O2 -Iengine/core engine/core/*.cpp engine/tests/tests.cpp -o /tmp/t -lpthread && /tmp/t`.
- `engine/android/jni.cpp` written, not compiled yet. Kotlin side must declare `io.github.corixien.viddaw.engine.Native` with matching externals.
- Shared Kotlin: Model, Effects, Edits, AudioEngine + snapshot, Platform interface.

Next:
1. Generate Gradle wrapper (`~/.gradle/wrapper/dists/gradle-9.8.1-bin/*/gradle-9.8.1/bin/gradle wrapper`), set `sdk.dir` in `local.properties`.
2. Shared: AppState (settings, project list), StudioState (undo, transport, recording), Theme, Strings, UI kit, Projects/Studio/Timeline/Mixer/Effects/PianoRoll/Pads/Settings/Export screens, model tests in `commonTest`.
3. androidApp: manifest (RECORD_AUDIO, BLUETOOTH_CONNECT), MainActivity, `Native` object, AndroidPlatform (MediaCodec import/export, MIDI, Media3 video), Inter fonts in `res/font`, adaptive icon (graphite tile, V from two clip bars).
4. Build, install via adb, screenshot-check each milestone, commit per milestone, push to `main`.
5. README, LICENSE (Apache-2.0), THIRD_PARTY_NOTICES, AGENTS.md, CI workflow, release v0.1 (keystore outside repo in `~/.config/viddaw/`).

## Notes
- A shell hook redirects commands containing build-tool names or curl to the context-mode `ctx_execute` tool.
- Verify the Lucide artifact API (`com.composables:icons-lucide-cmp:2.2.1`) on first compile.
