# Viddaw: notes for agents

Read `project-resume.md` first; it has the current state and the decisions behind it.

## Goal
DAW-style audio and video editor for Android, later desktop and web. Local only. Apache-2.0.

## Layout
- `engine/core`: portable C++20 engine. No platform includes here.
- `engine/android`: Oboe + JNI. JNI names bind to `io.github.corixien.viddaw.engine.Native`.
- `shared`: Kotlin Multiplatform (android + jvm). Model, edits, state, Compose UI. No Android APIs.
- `androidApp`: activity and everything that needs Android (pickers, MediaCodec, MIDI, Media3).

## Commands
- Build: `./gradlew :androidApp:assembleDebug`
- Kotlin tests: `./gradlew :shared:jvmTest`
- Engine tests: `g++ -std=c++20 -O2 -Iengine/core engine/core/*.cpp engine/tests/tests.cpp -o /tmp/engine_tests -lpthread && /tmp/engine_tests`
- Install: `adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk`, wait two seconds before starting the app.

## Invariants
- Effect parameter order in `shared/.../model/Effects.kt` must match `engine/core/effects.cpp`, and the defaults listed in `engine/tests/tests.cpp`.
- The snapshot format written by `engineSnapshot` (`shared/.../engine/AudioEngine.kt`) must match the parser in `engine/core/engine.cpp`; it is documented in `engine/core/engine.h`.
- Nothing on the audio thread may allocate, lock or do I/O. `Engine::render` only try-locks.
- Project edits are pure functions on immutable data (`model/Edits.kt`). UI changes go through `StudioState.edit`; wrap drags in `beginGesture`/`endGesture` so they are one undo step.
- All project audio is 32-bit float WAV at 48 kHz. Imports go through `Importer`.
- Every UI string lives in `ui/Strings.kt` with English and German.
- The playhead is the centre of the timeline; there is no scroll offset. Anything that maps ticks to pixels goes through `leftTick` in `ui/Timeline.kt`.
- Clip actions act on `StudioState.targets()` (selection plus groups), never on a single clip id.
- A video clip must look the same in preview and export: `VideoFx.colorMatrix` and `VideoFx.transitionAt` (`model/Video.kt`) feed both, and `drawOverlay` (`ui/ClipTools.kt`) is mirrored by `overlayBitmap` (`VideoSupport.kt`). Change them together.

## Design
Flat, no gradients, macOS feel, graphite accent. Colour only from tracks and clips, red for
recording. Own components in `ui/Kit.kt`, no Material widgets. Animations answer user actions.

## Licensing
Permissive dependencies only (Apache, MIT, BSD, ISC, OFL). No GPL or AGPL code, no proprietary
SDKs, no samples or assets from other products. Update `THIRD_PARTY_NOTICES.md` with every new dependency.

## Secrets
There are none in the repo. The release keystore and its passwords live in `~/.config/viddaw/` and must stay there.
