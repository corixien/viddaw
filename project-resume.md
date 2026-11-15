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
- Video: a clip on a Video track carries the source URI; its sound is an ordinary audio clip in the same
  group. The engine plays the sound; a muted ExoPlayer chases the engine position for preview. Export
  bounces the mix to WAV, then Media3 Transformer renders clips, gaps (black) and overlays over it.
- Host services go through `app/Platform.kt`; Android implementation in `androidApp/.../AndroidPlatform.kt`,
  `MidiInput.kt`, `VideoSupport.kt`.
- Projects live in `<filesDir>/projects/<id>/project.json` with `audio/*.wav` beside it.

## Current state (2026-11-15)
Version 0.2.0 in the tree; the published release is still 0.1.0.

0.2.0 is a rework of the studio asked for by the user after trying 0.1.0 ("behave like BandLab Mobile").
It builds, and the engine and model tests pass, but **none of it has run on a device yet**: the phone was
not connected. First job of the next session: install the debug build and walk through the list below.

What 0.2.0 changed (all untested on device):
- Playhead fixed in the middle of the timeline; the content scrolls under it, so scrolling is seeking
  (`ui/Timeline.kt`). `StudioState.scrollTick` is gone. Pinch zoom therefore always zooms on the playhead.
- Track headers reach to the playhead at the project start and shrink to 118 dp as the content scrolls in.
- Mixer is no longer a sheet. Swiping the headers right moves them to the right edge (wide headers travel
  first, then narrow) and shows one mixer strip per track row on the left; swipe left, the corner button or
  back closes it. State: `StudioState.mixerOpen`, progress animated in `Timeline`.
- Long-press a header and drag to reorder tracks. "+ Track" is the last row of the header column.
- The bar above the transport is gone. A selected clip gets a floating toolbar (`ui/ClipTools.kt`): split,
  copy, duplicate, delete as icons, the rest behind the last button. Long-pressing a clip adds it to the
  selection; long-pressing an empty spot offers paste, import or a new clip there.
- Selection is a set (`StudioState.selection`); actions apply to `targets()`, the selection plus its groups.
  Clips with the same non-zero `Clip.group` move, split, trim, duplicate and delete together.
- New clip fields: `fadeIn`, `fadeOut`, `group`, `src`, `speed`, `pitch`, `fx`, `overlay`. Fades are part of the
  engine's `C` record. Time-stretch and transpose render a new WAV with `stretchFile` (WSOLA plus sinc
  resampling, `engine/core/audio_file.cpp`) from the original in `src`; MIDI clips rescale their notes.
- Sheets close by dragging the handle or title down; tool sheets have no scrim so the picture stays usable.
- Project list to studio transition: one shared tween, studio always on top (`ui/App.kt`).
- Video import creates a video clip and an audio clip in one group, on a video track and an audio track
  of the same colour. Video clips show frames (`StudioState.thumbsFor`, `Platform.videoFrame`).
- Per video clip (`model/Video.kt`, `VideoFx`): colour (brightness, contrast, saturation, warmth, hue),
  six filters, blur, opacity, placement (size, position, rotation, mirror), transitions in and out
  (fade, four slides, zoom). Preview: TextureView with a colour-matrix layer paint and blur on Android,
  placement, fades and overlays drawn by the shared UI (`VideoBox` in `ui/Studio.kt`). Export: the same
  values as Media3 effects per clip (`VideoSupport.clipEffects`).
- Text, rectangle and ellipse overlays are clips on a video track (`Clip.overlay`), placed by dragging and
  pinching in the preview, exported as timed bitmap overlays.

Check on the device, in this order:
1. Timeline gestures: scroll and fling, pinch, tap and long-press on clips, drag-move of a multi-selection,
   trim handles, loop handles. Header gestures: tap, swipe to the mixer and back, long-press reorder.
2. Toolbar placement near screen edges and above the first track; sheets dragging shut.
3. Video import (two grouped tracks), frames in the clip, split and move staying in sync, ungroup.
4. Preview: colour, filters, blur (needs Android 12), placement by touch, transitions, overlays.
5. MP4 export with all of the above. Uncertain points: effect timestamps (handled by `Clock`, which counts
   from the first frame an effect sees), overlay timing at composition level, colour match between the
   preview's colour filter and the export (`WORKING_COLOR_SPACE_ORIGINAL`), TextureView transforms.
6. Fades, clip gain, normalise, time-stretch and transpose by ear.

Still true from 0.1.0 (verified on a Galaxy S23+, Android 17): recording, audio import, drum rack, sampler,
MIDI recording, piano roll, effects, WAV/FLAC/M4A export, plain MP4 export.

Never verified: USB and Bluetooth MIDI hardware, acoustic round-trip latency, tablets, API levels below 37.

Release:
- Signed with `~/.config/viddaw/viddaw-release.jks`, passwords in `~/.config/viddaw/keystore.properties`.
  Both are outside the repo and must be backed up; losing them means updates cannot install over old versions.
- The phone has the 0.1.0 debug build. The release APK has a different signature and will not install over it.
- Do not publish 0.2.0 before the device pass above.

## Known gaps and next steps
1. Device pass for 0.2.0 (see above), then fix what it shows and retake the README screenshots.
2. Video: clips never show at the same time, so there is no cross-dissolve and no picture-in-picture;
   transitions run against black. Needs a second decoder in the preview and a multi-sequence export.
3. Video clips have no speed control; time-stretch is offered for audio and MIDI only.
4. Overlays: no images or stickers, one line of text, no outline or shadow, no animation.
5. "Loop" in the clip toolbar sets the project loop to the clip; clips cannot repeat themselves yet.
6. While tracks are dragged to a new place only the headers move; clips follow on release.
7. Tempo-synced delay, automation, input monitoring, master effects.
8. Overlapping clips on one track both play; decide whether the later clip should cut the earlier one.
9. Piano roll: velocity editing; white keys are not drawn in the key column.
10. Desktop and web targets: add a JVM desktop app and a miniaudio or WASM audio backend behind `AudioEngine`.
11. Trim project audio no clip references any more (deleted clips, old stretch renders).

## Working notes
- A shell hook in this environment redirects commands containing build-tool names or curl to the
  context-mode `ctx_execute` tool.
- After `adb install`, wait two seconds before `am start`; otherwise Android kills the fresh process.
- There is no emulator on this machine; UI work can only be checked on the phone.
- The phone reports density 2.75; screenshots scaled to 36 % map to device pixels by dividing by 0.36.
- The host ffmpeg cannot decode the phone's H.264; use GStreamer `vah264dec` to check exported frames.
