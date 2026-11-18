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

## Latest batch (2026-11-18, built and installed, NOT tested on the device)
- Mixer: swipe starting on header buttons works (header watches the initial pointer pass; buttons cancel once it consumes). Tapping a header opens track settings directly; move up/down buttons removed.
- Phone landscape (shorter side < 600 dp): transport is a column right of the picture (`TransportColumn` in `ui/Studio.kt`); tablets keep the bar.
- Video: preview hides at once between clips (graphicsLayer alpha 0, was the "last frame again" bug). `Project.frame` picks the picture format (Auto/16:9/9:16/1:1/4:3, Tempo sheet); export uses it (`exportVideo(..., aspect, ...)`). Cause of the landscape-after-portrait problem: aspect came from the first clip only.
- Preview editing: snapping to centre and other objects (guides), rotation snaps every 45 deg, grid toggle (`StudioState.showGrid`), Shapes icon for text/shape.
- `StudioState.gridTicks()` follows the drawn grid lines at every zoom.
- Clip toolbar: unfolded list is a popup under the bar with the bar's width; bar only moves up when the screen ends.
- Clips are clipped to their rounded outline (fade lines, thumbnails).
- Double tap on a slider resets it (`HSlider.reset`, centred sliders reset to the middle).
- EQ: draggable five-band graph (`ui/EqEditor.kt`); new params 12-14 (Mid 3) appended; snapshot pads missing params with defaults.
- Automation (`model/Automation.kt`): `Track.lanes`, targets vol, pan, `fx:<id>:<param>`, video `v:<field>` (no blur). Engine record `A` (see `engine.h`), lane overrides the fixed setting, evaluated per block at the block start. Video lanes are laid over `VideoFx` through `withAuto` for preview and export. UI: Spline button in the top bar shows lanes under tracks (`StudioState.showAuto`, `rows()` in `ui/Timeline.kt` gives variable row heights); tap lane = add point, drag point = move, double tap point = delete; "+ Automation" row under the selected track. Track reordering by long press is off while lanes are shown.
- Faders: `faderToDb`/`dbToFader` moved to `model/Automation.kt`.
- Not verified: everything above on the device; the mixer strips do not show lane-driven values.

## Current state (2026-11-15)
Version 0.2.0 in the tree and installed on the phone as a debug build; the published release is still 0.1.0.

0.2.0 is a rework of the studio asked for by the user after trying 0.1.0 ("behave like BandLab Mobile").

What 0.2.0 changed:
- Playhead fixed in the middle of the timeline; the content scrolls under it, so scrolling is seeking
  (`ui/Timeline.kt`). `StudioState.scrollTick` is gone. Pinch zoom therefore always zooms on the playhead.
- Track headers reach to the playhead at the project start and shrink to 118 dp as the content scrolls in.
- Mixer is no longer a sheet. Swiping the headers right moves them to the right edge (wide headers travel
  first, then narrow) and shows one mixer strip per track row on the left; swipe left, the corner button or
  back closes it. State: `StudioState.mixerOpen`, progress animated in `Timeline`. The header column moves
  under the finger, so its swipe is measured in timeline coordinates (`left()` in `HeaderColumn`).
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

Verified on the Galaxy S23+ (Android 17) with adb-driven touches:
- Centred playhead, scrolling, headers shrinking from half width; clip selection, long-press multi-selection,
  toolbar and its overflow; mixer swipe open and closed; header reorder; sheet dragged shut.
- Video import into two grouped tracks of one colour with frames; split cuts both; drag moves both.
- Colour grade and zoom transition in the preview; text overlay added and dragged in the preview.
- MP4 export of that project: frames checked on the host show the grade, the transition growing in and
  the text for its three seconds.
- Time-stretch on a recorded take: the rendered file has the expected length.

Not verified on the device:
- By ear: fades, clip gain, normalise, the sound of a stretched or transposed clip.
- By real fingers: pinch zoom, two-finger resize and rotate in the preview, trim and loop handles, fling feel,
  whether the project/studio transition now looks clean (the user reported the old one as glitchy).
- Blur, filters, placement and slide transitions in the export; rectangle and ellipse overlays; paste.
- Whether preview and export colours match exactly.

Still true from 0.1.0 (verified on a Galaxy S23+, Android 17): recording, audio import, drum rack, sampler,
MIDI recording, piano roll, effects, WAV/FLAC/M4A export, plain MP4 export.

Never verified: USB and Bluetooth MIDI hardware, acoustic round-trip latency, tablets, API levels below 37.

Release:
- Signed with `~/.config/viddaw/viddaw-release.jks`, passwords in `~/.config/viddaw/keystore.properties`.
  Both are outside the repo and must be backed up; losing them means updates cannot install over old versions.
- The phone has the 0.2.0 debug build. The release APK has a different signature and will not install over it.
- 0.2.0 is not published; the user has not tried it yet.

## Known gaps and next steps
1. Device test of the 2026-11-18 batch, user feedback on 0.2.0, the unverified points above, new README screenshots, then a 0.2.0 release.
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
- `adb shell input draganddrop x1 y1 x2 y2 ms` does a long-press drag; plain `input swipe` cannot.
- The floating toolbar moves with the selection, so take a screenshot before every tap on it.
- The host ffmpeg cannot decode the phone's H.264; use GStreamer `vah264dec` to check exported frames.
