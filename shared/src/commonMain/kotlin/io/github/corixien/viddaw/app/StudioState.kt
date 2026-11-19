package io.github.corixien.viddaw.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.corixien.viddaw.engine.AudioEngine
import io.github.corixien.viddaw.engine.engineSnapshot
import androidx.compose.ui.graphics.ImageBitmap
import io.github.corixien.viddaw.model.Clip
import io.github.corixien.viddaw.model.Overlay
import io.github.corixien.viddaw.model.OVERLAY_IMAGE
import io.github.corixien.viddaw.model.VideoAuto
import io.github.corixien.viddaw.model.laneValue
import io.github.corixien.viddaw.model.clips
import io.github.corixien.viddaw.model.duplicateClips
import io.github.corixien.viddaw.model.groupClips
import io.github.corixien.viddaw.model.insertCopies
import io.github.corixien.viddaw.model.mapClips
import io.github.corixien.viddaw.model.removeClips
import io.github.corixien.viddaw.model.splitClips
import io.github.corixien.viddaw.model.stretchedNotes
import io.github.corixien.viddaw.model.transposedNotes
import io.github.corixien.viddaw.model.withGroups
import io.github.corixien.viddaw.model.MIN_DB
import kotlin.math.log10
import kotlin.math.roundToInt
import io.github.corixien.viddaw.model.Note
import io.github.corixien.viddaw.model.PPQ
import io.github.corixien.viddaw.model.Project
import io.github.corixien.viddaw.model.SAMPLE_RATE
import io.github.corixien.viddaw.model.Track
import io.github.corixien.viddaw.model.TrackType
import io.github.corixien.viddaw.model.addClip
import io.github.corixien.viddaw.model.addTrack
import io.github.corixien.viddaw.model.clip
import io.github.corixien.viddaw.model.endTick
import io.github.corixien.viddaw.model.framesToTicks
import io.github.corixien.viddaw.model.isMidi
import io.github.corixien.viddaw.model.mapTrack
import io.github.corixien.viddaw.model.ticksPerBar
import io.github.corixien.viddaw.model.ticksToFrames
import io.github.corixien.viddaw.model.track
import io.github.corixien.viddaw.model.trackOfClip
import io.github.corixien.viddaw.ui.Str
import io.github.corixien.viddaw.ui.T
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which sheet is open over the studio. Held in state so system back can close it. */
sealed interface Panel {
    data object None : Panel
    data object AddTrack : Panel
    data object Tempo : Panel
    data object Export : Panel
    data class TrackSettings(val trackId: Long) : Panel
    data class Effects(val trackId: Long) : Panel
    data class NoteEditor(val clipId: Long) : Panel
    data class Play(val trackId: Long) : Panel
    data class ClipTool(val tool: Tool) : Panel
    data object AddOverlay : Panel
    data class AddLane(val trackId: Long) : Panel
}

/** The lanes of a video track whose target starts with [prefix], in seconds and real values. */
fun Track.autosFor(prefix: String, bpm: Double): List<VideoAuto> = lanes.mapNotNull { l ->
    if (!l.target.startsWith(prefix) || l.points.isEmpty()) return@mapNotNull null
    VideoAuto(
        l.target.removePrefix(prefix), DoubleArray(l.points.size) { ticksToFrames(l.points[it].tick, bpm).toDouble() / SAMPLE_RATE },
        FloatArray(l.points.size) { laneValue(l.target, l.points[it].value) ?: 0f },
    )
}

/** Aspect ratios behind [Project.frame]; 0 follows the first video. */
val FRAME_ASPECTS = listOf(0f, 16f / 9f, 9f / 16f, 1f, 4f / 3f)

/** Editors for the selected clips that need more than one tap. */
enum class Tool { Fade, Gain, Stretch, Pitch, Color, Look, Transform, Transition, Text }

/** An empty place on a track that was long-pressed: where a paste or a new clip would go. */
class Spot(val trackId: Long, val tick: Long)

/** Preview frames of one video, [stepSec] apart. Grows while they load. */
class Thumbs(val stepSec: Double, val frames: List<ImageBitmap>)

/** State and actions of one open project. Main thread only. */
private const val PEAK = io.github.corixien.viddaw.engine.PEAK_FRAMES

class StudioState(
    initial: Project,
    val dir: String,
    private val engine: AudioEngine,
    private val platform: Platform,
    private val scope: CoroutineScope,
    private val settings: () -> Settings,
    private val persist: (Project) -> Unit,
) {
    var project by mutableStateOf(initial)
        private set
    private val undoStack = ArrayDeque<Project>()
    private val redoStack = ArrayDeque<Project>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    private var gestureBase: Project? = null
    private var saveJob: Job? = null

    var selectedTrack by mutableStateOf(initial.tracks.firstOrNull()?.id)
    /** Selected clips in the order they were picked; the last one is the primary. */
    var selection by mutableStateOf<Set<Long>>(emptySet())
        private set
    val selectedClip: Long? get() = selection.lastOrNull()
    var spot by mutableStateOf<Spot?>(null)
    var mixerOpen by mutableStateOf(false)
    var clipboard by mutableStateOf<List<Pair<Long, Clip>>>(emptyList())
        private set
    var panel by mutableStateOf<Panel>(Panel.None)

    var playing by mutableStateOf(false)
        private set
    var positionFrames by mutableLongStateOf(0)
        private set
    var recording by mutableStateOf(false)
        private set
    var recordTrack by mutableStateOf<Long?>(null)
        private set
    var recordStartFrame by mutableLongStateOf(0)
        private set
    var recordPeaks by mutableStateOf(FloatArray(0))
        private set
    private var recordFile: String? = null
    private val heldNotes = HashMap<Int, Pair<Long, Int>>()
    private val takenNotes = ArrayList<Note>()

    /** Smoothed peak levels: master L, master R, then tracks in order. Read [meterTick] to observe. */
    val levels = FloatArray(130)
    private val rawLevels = FloatArray(130)
    var meterTick by mutableIntStateOf(0)
        private set

    /** Timeline view: width of one beat in dp. The playhead stays in the middle, so the position is the scroll. */
    var beatWidth by mutableFloatStateOf(36f)
    var snapOn by mutableStateOf(true)
    var showGrid by mutableStateOf(false)
    /** Automation lanes are drawn below their tracks. */
    var showAuto by mutableStateOf(false)

    /** Width over height of the picture, taken from the first video that loads. */
    var videoAspect by mutableFloatStateOf(16f / 9f)

    /** Width over height of the picture the project is cut in. */
    val frameAspect: Float get() = FRAME_ASPECTS.getOrElse(project.frame) { 0f }.takeIf { it > 0f } ?: videoAspect
    val thumbs = mutableStateMapOf<String, Thumbs>()
    private val thumbsLoading = HashSet<String>()

    val peaks = mutableStateMapOf<String, FloatArray>()
    private val peaksLoading = HashSet<String>()

    var busy by mutableStateOf<Str?>(null)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var message by mutableStateOf<String?>(null)
    var notice by mutableStateOf<Str?>(null)

    val positionTick: Long get() = framesToTicks(positionFrames, project.bpm)

    init {
        sync()
        engine.seek(0)
        platform.onMidiNote = { pitch, velocity, on -> if (on) noteOn(pitch, velocity) else noteOff(pitch) }
    }

    // ---- editing ----

    private fun sync() = engine.sync(engineSnapshot(project, dir))

    private fun apply(next: Project) {
        if (next == project) return
        project = next
        sync()
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(600)
            saveNow()
        }
    }

    private fun pushUndo(p: Project) {
        undoStack.addLast(p)
        if (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
        canUndo = true
        canRedo = false
    }

    /** One undoable change. Inside a gesture the whole gesture becomes the undo step instead. */
    fun edit(f: (Project) -> Project) {
        val before = project
        val next = f(before)
        if (next == before) return
        if (gestureBase == null) pushUndo(before)
        apply(next)
    }

    /** Brackets a continuous change such as a drag or a slider move. */
    fun beginGesture() {
        if (gestureBase == null) gestureBase = project
    }

    fun endGesture() {
        val base = gestureBase ?: return
        gestureBase = null
        if (base != project) pushUndo(base)
    }

    fun undo() {
        val p = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(project)
        afterHistory(p)
    }

    fun redo() {
        val p = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(project)
        afterHistory(p)
    }

    private fun afterHistory(p: Project) {
        apply(p)
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        selection = selection.filter { p.clip(it) != null }.toSet()
        spot = null
        if (selectedTrack?.let(p::track) == null) selectedTrack = p.tracks.firstOrNull()?.id
    }

    fun saveNow() {
        saveJob?.cancel()
        persist(project.copy(modifiedAt = platform.nowMillis()))
    }

    fun close() {
        if (recording) stopRecording()
        engine.pause()
        platform.onMidiNote = null
        saveNow()
        engine.sync("P 24000 4 0 0 0 1 0\n")
    }

    fun back(): Boolean {
        when {
            panel != Panel.None -> panel = Panel.None
            spot != null -> spot = null
            selection.isNotEmpty() -> selection = emptySet()
            mixerOpen -> mixerOpen = false
            else -> return false
        }
        return true
    }

    /** Grid for moving and trimming: the finest line the timeline draws at this zoom. 0 when snapping is off. */
    fun gridTicks(): Long {
        if (!snapOn) return 0
        if (beatWidth >= 90f) return PPQ / 4
        if (beatWidth >= 14f) return PPQ
        // Bar lines are drawn every bar while a bar is wide enough, else at the label spacing.
        val barDp = beatWidth * project.beatsPerBar
        var step = 1
        if (barDp <= 20f) while (barDp * step < 56f) step *= 2
        return project.ticksPerBar * step
    }

    fun addTrack(type: TrackType, name: String) {
        edit { it.addTrack(type, name) }
        selectTrack(project.tracks.last().id)
    }

    /** Selecting a track drops a clip selection that belongs to another track. */
    fun selectTrack(id: Long) {
        selectedTrack = id
        spot = null
        if (selection.any { project.trackOfClip(it)?.id != id }) selection = emptySet()
    }

    fun selectClip(id: Long?) {
        spot = null
        if (panel is Panel.ClipTool && id != selectedClip) panel = Panel.None
        selection = if (id == null) emptySet() else setOf(id)
        id?.let { project.trackOfClip(it) }?.let { selectedTrack = it.id }
    }

    /** Adds a clip to the selection, or takes it out again. */
    fun toggleClip(id: Long) {
        spot = null
        if (panel is Panel.ClipTool) panel = Panel.None
        selection = if (id in selection) selection - id else selection + id
        selectedClip?.let { project.trackOfClip(it) }?.let { selectedTrack = it.id }
    }

    /** Everything an action applies to: the selection and the groups it touches. */
    fun targets(): Set<Long> = project.withGroups(selection)

    val selectionGrouped: Boolean get() = project.clips(selection).any { it.group != 0L }

    fun editClips(f: (Clip) -> Clip) {
        val ids = targets()
        edit { it.mapClips(ids, f) }
    }

    fun splitSelected() {
        val ids = targets()
        edit { it.splitClips(ids, positionTick) }
    }

    fun deleteSelected() {
        val ids = targets()
        selection = emptySet()
        edit { it.removeClips(ids) }
    }

    private fun selectNewSince(firstId: Long) {
        val made = project.tracks.flatMap { t -> t.clips.filter { it.id >= firstId }.map { it.id } }
        if (made.isNotEmpty()) selection = made.toSet()
    }

    fun duplicateSelected() {
        val ids = targets()
        val next = project.nextId
        edit { it.duplicateClips(ids) }
        selectNewSince(next)
    }

    fun copySelected() {
        val ids = targets()
        clipboard = project.tracks.flatMap { t -> t.clips.filter { it.id in ids }.map { t.id to it } }
        if (clipboard.isNotEmpty()) notice = T.copied
    }

    /** Pastes the clipboard so its first clip starts at [tick]; clips keep their tracks where possible. */
    fun paste(trackId: Long?, tick: Long) {
        val first = clipboard.minOfOrNull { it.second.start } ?: return
        val next = project.nextId
        spot = null
        edit { it.insertCopies(clipboard, tick - first, trackId) }
        selectNewSince(next)
    }

    fun groupSelected(on: Boolean) {
        val ids = if (on) selection else targets()
        edit { it.groupClips(ids, on) }
    }

    /** Loops playback over the selected clips; a second call on the same range turns the loop off. */
    fun loopSelected() {
        val clips = project.clips(targets())
        if (clips.isEmpty()) return
        val start = clips.minOf { it.start }
        val end = clips.maxOf { it.endTick(project.bpm) }
        edit { if (it.loopEnabled && it.loopStart == start && it.loopEnd == end) it.copy(loopEnabled = false) else it.copy(loopEnabled = true, loopStart = start, loopEnd = end) }
    }

    /** Sets the gain of each selected audio clip so its loudest peak sits just below full scale. */
    fun normalizeSelected() = scope.launch {
        val clips = project.clips(targets()).filter { it.isAudio }
        val gains = HashMap<Long, Float>()
        for (c in clips) {
            val file = c.file ?: continue
            val data = peaks[file] ?: withContext(Dispatchers.Default) { engine.peaks("$dir/$file") }.also { peaks[file] = it }
            val from = (c.offset / PEAK).toInt().coerceIn(0, data.size)
            val to = ((c.offset + c.frames) / PEAK + 1).toInt().coerceIn(from, data.size)
            var peak = 0f
            for (i in from until to) if (data[i] > peak) peak = data[i]
            if (peak > 0.0005f) gains[c.id] = (-20f * log10(peak) - 0.2f).coerceIn(-24f, 36f)
        }
        if (gains.isEmpty()) return@launch
        edit { it.mapClips(gains.keys) { c -> c.copy(gainDb = (gains.getValue(c.id) * 10).roundToInt() / 10f) } }
        notice = T.normalized
    }

    /**
     * Sets speed and pitch of the selected clips. Audio is rendered again from its original file,
     * MIDI notes are rescaled and shifted. Video clips keep their speed.
     */
    fun stretchSelected(speed: Float, pitch: Float) = scope.launch {
        val ids = targets()
        val clips = project.clips(ids)
        if (clips.any { it.video != null }) return@launch
        val rendered = HashMap<Long, Pair<String, Long>>()
        val audio = clips.filter { it.isAudio && (it.speed != speed || it.pitch != pitch) }
        if (audio.isNotEmpty()) {
            busy = T.processing
            progress = 0.05f
            audio.forEachIndexed { i, c ->
                val source = c.src ?: c.file!!
                val file = "audio/st_${platform.nowMillis()}_${c.id}.wav"
                val frames = withContext(Dispatchers.Default) { engine.stretch("$dir/$source", "$dir/$file", speed.toDouble(), pitch.toDouble()) }
                if (frames > 0) rendered[c.id] = file to frames
                progress = (i + 1f) / audio.size
            }
            busy = null
            if (rendered.size < audio.size) notice = T.processFailed
        }
        edit { p ->
            p.mapClips(ids) { c ->
                when {
                    c.isMidi -> c.stretchedNotes(speed / c.speed).transposedNotes((pitch - c.pitch).roundToInt()).copy(speed = speed, pitch = pitch)
                    c.isAudio -> rendered[c.id]?.let { (file, frames) ->
                        val k = c.speed / speed
                        val offset = (c.offset * k).toLong().coerceIn(0, (frames - 1).coerceAtLeast(0))
                        c.copy(
                            file = file, src = c.src ?: c.file, speed = speed, pitch = pitch, fileFrames = frames, offset = offset,
                            frames = (c.frames * k).toLong().coerceIn(1, frames - offset),
                            fadeIn = (c.fadeIn * k).toLong(), fadeOut = (c.fadeOut * k).toLong(),
                        )
                    } ?: c
                    else -> c
                }
            }
        }
    }

    fun addMidiClip(track: Track, atTick: Long): Long {
        val bar = project.ticksPerBar
        val id = project.nextId
        val bars = if (track.type == TrackType.Drums) 1 else 4
        edit { it.addClip(track.id, Clip(0, (atTick / bar) * bar, length = bar * bars)) }
        selectClip(id)
        return id
    }

    /** Puts a text or shape over the picture at the playhead, on a video track. */
    fun addOverlay(kind: Int, text: String) {
        val at = positionTick
        beginGesture()
        val target = selectedTrack?.let(project::track)?.takeIf { it.type == TrackType.Video }?.id
            ?: project.tracks.firstOrNull { it.type == TrackType.Video }?.id
            ?: run {
                addTrack(TrackType.Video, "Video")
                project.tracks.last().id
            }
        val id = project.nextId
        val name = if (kind == 0) text else if (kind == 1) "Rectangle" else "Ellipse"
        edit { it.addClip(target, Clip(0, at, name, length = framesToTicks(SAMPLE_RATE * 3L, it.bpm), overlay = Overlay(kind, text, size = if (kind == 0) 0.12f else 0.3f))) }
        endGesture()
        selectClip(id)
        panel = Panel.ClipTool(Tool.Text)
    }

    // ---- transport ----

    /** Called once per display frame while the studio is visible. */
    fun tick() {
        if (busy != null) return  // an offline render is driving the engine
        positionFrames = engine.position()
        playing = engine.isPlaying()
        val n = engine.meters(rawLevels)
        for (i in levels.indices) levels[i] = maxOf(if (i < n) rawLevels[i] else 0f, levels[i] * 0.86f)
        meterTick++
        if (recordFile != null) {
            val more = engine.recordingPeaks(recordPeaks.size)
            if (more.isNotEmpty()) recordPeaks += more
        }
    }

    fun togglePlay() {
        if (recording) return stopRecording()
        if (engine.isPlaying()) engine.pause() else engine.play()
    }

    fun seekFrame(frame: Long) {
        val f = frame.coerceAtLeast(0)
        engine.seek(f)
        positionFrames = f
    }

    fun seekTick(tick: Long) = seekFrame(ticksToFrames(tick.coerceAtLeast(0), project.bpm))

    fun toStart() = seekFrame(0)

    fun toggleRecord() {
        if (recording) stopRecording() else scope.launch { startRecording() }
    }

    private suspend fun startRecording() {
        val track = selectedTrack?.let(project::track)
        if (track == null || track.type == TrackType.Video) {
            notice = T.selectTrackToRecord
            return
        }
        if (track.type == TrackType.Audio) {
            if (!platform.requestMicPermission()) {
                notice = T.micDenied
                return
            }
            val file = "audio/rec_${platform.nowMillis()}.wav"
            platform.mkdirs("$dir/audio")
            if (!engine.startRecording("$dir/$file")) {
                notice = T.recordFailed
                return
            }
            recordFile = file
            recordPeaks = FloatArray(0)
        }
        heldNotes.clear()
        takenNotes.clear()
        recordTrack = track.id
        recordStartFrame = engine.position()
        recording = true
        engine.play()
    }

    private fun stopRecording() {
        val trackId = recordTrack
        val endTick = positionTick
        engine.pause()
        recording = false
        recordTrack = null
        val file = recordFile
        recordFile = null
        if (file != null) {
            val rec = engine.stopRecording()
            // What was captured lags what was heard by the device round trip; skip that much.
            val lag = (engine.latencyFrames() + settings().recordOffsetMs * SAMPLE_RATE / 1000L).coerceIn(0L, rec.frames)
            val frames = rec.frames - lag
            if (trackId != null && frames > SAMPLE_RATE / 20) {
                val id = project.nextId
                edit {
                    it.addClip(trackId, Clip(0, framesToTicks(rec.startFrame, it.bpm), "Take", file, lag, frames, rec.frames))
                }
                selectClip(id)
            } else {
                platform.delete("$dir/$file")
            }
            return
        }
        for ((pitch, held) in heldNotes) takenNotes += Note(held.first, (endTick - held.first).coerceAtLeast(PPQ / 8), pitch, held.second)
        heldNotes.clear()
        if (trackId == null || takenNotes.isEmpty()) return
        val bar = project.ticksPerBar
        val start = (takenNotes.minOf { it.start } / bar) * bar
        val end = ((takenNotes.maxOf { it.start + it.length } + bar - 1) / bar) * bar
        val notes = takenNotes.map { it.copy(start = it.start - start) }
        val id = project.nextId
        edit { it.addClip(trackId, Clip(0, start, length = end - start, notes = notes)) }
        selectClip(id)
    }

    // ---- instruments ----

    fun noteOn(pitch: Int, velocity: Int, trackId: Long? = selectedTrack) {
        val track = trackId?.let(project::track)?.takeIf { it.type.isMidi } ?: return
        engine.noteOn(track.id, pitch, velocity)
        if (recording && recordTrack == track.id) heldNotes[pitch] = positionTick to velocity
    }

    fun noteOff(pitch: Int, trackId: Long? = selectedTrack) {
        val track = trackId?.let(project::track)?.takeIf { it.type.isMidi } ?: return
        engine.noteOff(track.id, pitch)
        val held = heldNotes.remove(pitch) ?: return
        takenNotes += Note(held.first, (positionTick - held.first).coerceAtLeast(PPQ / 8), pitch, held.second)
    }

    /** Short preview of a note, used when placing notes in the editor. */
    fun audition(trackId: Long, pitch: Int, velocity: Int = 100) {
        engine.noteOn(trackId, pitch, velocity)
        scope.launch {
            delay(180)
            engine.noteOff(trackId, pitch)
        }
    }

    // ---- media ----

    fun peaksFor(file: String): FloatArray? {
        peaks[file]?.let { return it }
        if (peaksLoading.add(file)) {
            scope.launch {
                val data = withContext(Dispatchers.Default) { engine.peaks("$dir/$file") }
                peaks[file] = data
                peaksLoading.remove(file)
            }
        }
        return null
    }

    private suspend fun import(uri: String, prefix: String): Pair<String, Long>? {
        val file = "audio/${prefix}_${platform.nowMillis()}.wav"
        platform.mkdirs("$dir/audio")
        busy = T.importing
        progress = 0f
        val frames = platform.importAudio(uri, "$dir/$file") { progress = it }
        busy = null
        return if (frames > 0) file to frames else null
    }

    /** Imports a file onto the selected audio track, or a new one, at the playhead. */
    fun importAudio() = scope.launch {
        val picked = platform.pickAudio() ?: return@launch
        val (file, frames) = import(picked.uri, "imp") ?: run {
            notice = T.importFailed
            return@launch
        }
        val at = positionTick
        beginGesture()
        val target = selectedTrack?.let(project::track)?.takeIf { it.type == TrackType.Audio }?.id ?: run {
            addTrack(TrackType.Audio, picked.name.substringBeforeLast('.').take(24))
            project.tracks.last().id
        }
        val id = project.nextId
        edit { it.addClip(target, Clip(0, at, picked.name.substringBeforeLast('.'), file, 0, frames, frames)) }
        endGesture()
        selectClip(id)
    }

    /**
     * Imports a video as two grouped clips: the picture on a video track and its sound on an
     * audio track of the same colour, so both are cut and moved together until they are ungrouped.
     */
    fun importVideo(newTracks: Boolean = false) = scope.launch {
        val picked = platform.pickVideo() ?: return@launch
        val durationMs = platform.videoDurationMs(picked.uri)
        if (durationMs <= 0) {
            notice = T.importFailed
            return@launch
        }
        val audio = import(picked.uri, "vid")
        val frames = durationMs * SAMPLE_RATE / 1000
        val at = positionTick
        val name = picked.name.substringBeforeLast('.')
        beginGesture()
        val videoTrack = selectedTrack?.let(project::track)?.takeIf { !newTracks && it.type == TrackType.Video } ?: run {
            edit { it.addTrack(TrackType.Video, "Video ${it.tracks.count { t -> t.type == TrackType.Video } + 1}") }
            project.tracks.last()
        }
        val first = project.nextId
        if (audio == null) {
            edit { it.addClip(videoTrack.id, Clip(0, at, name, null, 0, frames, frames, video = picked.uri)) }
        } else {
            // The sound goes to the audio track right below the video track if that is its partner.
            val below = project.tracks.getOrNull(project.tracks.indexOfFirst { it.id == videoTrack.id } + 1)
            val endTick = at + framesToTicks(frames, project.bpm)
            val free = below?.clips?.none { it.start < endTick && it.endTick(project.bpm) > at } == true
            val audioTrack = below?.takeIf { !newTracks && free && it.type == TrackType.Audio && it.color == videoTrack.color } ?: run {
                edit { it.addTrack(TrackType.Audio, "${videoTrack.name} Audio".take(24), videoTrack.color) }
                project.tracks.last()
            }
            val clipsFirst = project.nextId
            edit { p ->
                val group = p.nextId
                p.copy(nextId = group + 1)
                    .addClip(videoTrack.id, Clip(0, at, name, null, 0, frames, frames, video = picked.uri, group = group))
                    .addClip(audioTrack.id, Clip(0, at, name, audio.first, 0, minOf(frames, audio.second), audio.second, group = group))
            }
            endGesture()
            selectedTrack = videoTrack.id
            selection = setOf(clipsFirst + 1)
            return@launch
        }
        endGesture()
        selectClip(first)
    }

    /** Preview frames for a video clip; loading starts on first use. */
    fun thumbsFor(uri: String, fileFrames: Long): Thumbs? {
        thumbs[uri]?.let { return it }
        if (thumbsLoading.add(uri)) {
            scope.launch {
                val seconds = fileFrames.toDouble() / SAMPLE_RATE
                val count = seconds.toInt().coerceIn(4, 48)
                val step = seconds / count
                val frames = ArrayList<ImageBitmap>()
                for (i in 0 until count) {
                    val frame = platform.videoFrame(uri, ((i + 0.5) * step * 1000).toLong(), 96) ?: continue
                    frames += frame
                    thumbs[uri] = Thumbs(step, frames.toList())
                }
            }
        }
        return null
    }

    /** Loads a sample for the sampler, or for one drum pad when [pad] is given. */
    fun loadSample(trackId: Long, pad: Int? = null) = scope.launch {
        val picked = platform.pickAudio() ?: return@launch
        val (file, _) = import(picked.uri, "smp") ?: run {
            notice = T.importFailed
            return@launch
        }
        val name = picked.name.substringBeforeLast('.').take(24)
        edit { p ->
            p.mapTrack(trackId) { t ->
                if (pad == null) t.copy(sampler = t.sampler.copy(file = file, name = name))
                else t.copy(pads = t.pads.mapIndexed { i, d -> if (i == pad) d.copy(file = file, name = name) else d })
            }
        }
    }

    /** Video clips of all video tracks in timeline order, trimmed so they never overlap. */
    fun videoSegments(): List<VideoSegment> {
        val bpm = project.bpm
        val entries = project.tracks.filter { it.type == TrackType.Video && !it.mute }.flatMap { t ->
            val autos = t.autosFor("v:", bpm)
            t.clips.filter { it.video != null }.map { it to autos }
        }.sortedBy { it.first.start }
        return entries.mapIndexedNotNull { i, (c, autos) ->
            val start = ticksToFrames(c.start, bpm)
            val nextStart = entries.getOrNull(i + 1)?.let { ticksToFrames(it.first.start, bpm) } ?: Long.MAX_VALUE
            val frames = minOf(c.frames, nextStart - start)
            if (frames <= 0 || c.video == null) null
            else VideoSegment(c.video, start.toDouble() / SAMPLE_RATE, c.offset.toDouble() / SAMPLE_RATE, frames.toDouble() / SAMPLE_RATE, c.fx, c.id, autos)
        }
    }

    /** Texts and shapes of all video tracks; later tracks are drawn on top. */
    fun overlaySegments(): List<OverlaySegment> {
        val bpm = project.bpm
        return project.tracks.filter { it.type == TrackType.Video && !it.mute }.flatMap { t ->
            val autos = t.autosFor("o:", bpm)
            t.clips.mapNotNull { c ->
                val o = c.overlay ?: return@mapNotNull null
                val start = ticksToFrames(c.start, bpm)
                OverlaySegment(o, start.toDouble() / SAMPLE_RATE, (ticksToFrames(c.start + c.length, bpm) - start).toDouble() / SAMPLE_RATE, c.id, autos)
            }
        }
    }

    /** Puts a picture over the video at the playhead, three seconds long; drag its end to change that. */
    fun importImage() = scope.launch {
        val picked = platform.pickImage() ?: return@launch
        val bitmap = platform.loadImage(picked.uri, 1280) ?: run {
            notice = T.importFailed
            return@launch
        }
        val at = positionTick
        val aspect = bitmap.width.toFloat() / bitmap.height
        // As large as fits the picture frame.
        val size = minOf(1f, frameAspect / aspect)
        beginGesture()
        val target = selectedTrack?.let(project::track)?.takeIf { it.type == TrackType.Video }?.id
            ?: project.tracks.firstOrNull { it.type == TrackType.Video }?.id
            ?: run {
                addTrack(TrackType.Video, "Video")
                project.tracks.last().id
            }
        val id = project.nextId
        images[picked.uri] = bitmap
        edit { it.addClip(target, Clip(0, at, picked.name.substringBeforeLast('.'), length = framesToTicks(SAMPLE_RATE * 3L, it.bpm), overlay = Overlay(OVERLAY_IMAGE, picked.uri, size = size, aspect = aspect))) }
        endGesture()
        selectClip(id)
    }

    fun overlayFont(index: Int) = platform.overlayFont(index)

    val images = mutableStateMapOf<String, ImageBitmap>()
    private val imagesLoading = HashSet<String>()

    /** A picture overlay's bitmap; loading starts on first use. */
    fun imageFor(uri: String): ImageBitmap? {
        images[uri]?.let { return it }
        if (imagesLoading.add(uri)) scope.launch { platform.loadImage(uri, 1280)?.let { images[uri] = it } }
        return null
    }

    val hasVideo: Boolean get() = project.tracks.any { t -> t.type == TrackType.Video && t.clips.isNotEmpty() }

    fun export(format: ExportFormat) = scope.launch {
        val end = ticksToFrames(project.endTick(), project.bpm)
        if (end <= 0) {
            notice = T.exportEmpty
            return@launch
        }
        if (recording) stopRecording()
        saveNow()
        busy = T.exporting
        progress = 0f
        val wav = "$dir/export.wav"
        // Two seconds of tail let reverbs and delays ring out; video keeps the exact length.
        val tail = if (format.video) 0 else SAMPLE_RATE * 2
        val poll = launch {
            while (true) {
                progress = engine.renderProgress() * if (format.video) 0.3f else 0.9f
                delay(100)
            }
        }
        val rendered = withContext(Dispatchers.Default) { engine.renderToFile(wav, 0, end + tail, if (format == ExportFormat.Wav) 24 else 16) }
        poll.cancel()
        val where = when {
            !rendered -> null
            format.video -> platform.exportVideo(videoSegments(), overlaySegments(), wav, end.toDouble() / SAMPLE_RATE, project.name, FRAME_ASPECTS.getOrElse(project.frame) { 0f }) { progress = 0.3f + it * 0.7f }
            else -> platform.exportAudio(wav, format, project.name)
        }
        platform.delete(wav)
        busy = null
        panel = Panel.None
        if (where != null) message = where else notice = T.exportFailed
    }
}
