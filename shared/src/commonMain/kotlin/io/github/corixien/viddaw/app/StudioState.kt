package io.github.corixien.viddaw.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.corixien.viddaw.engine.AudioEngine
import io.github.corixien.viddaw.engine.engineSnapshot
import io.github.corixien.viddaw.model.Clip
import io.github.corixien.viddaw.model.Note
import io.github.corixien.viddaw.model.PPQ
import io.github.corixien.viddaw.model.Project
import io.github.corixien.viddaw.model.SAMPLE_RATE
import io.github.corixien.viddaw.model.Track
import io.github.corixien.viddaw.model.TrackType
import io.github.corixien.viddaw.model.addClip
import io.github.corixien.viddaw.model.addTrack
import io.github.corixien.viddaw.model.clip
import io.github.corixien.viddaw.model.duplicateClip
import io.github.corixien.viddaw.model.endTick
import io.github.corixien.viddaw.model.framesToTicks
import io.github.corixien.viddaw.model.isMidi
import io.github.corixien.viddaw.model.mapTrack
import io.github.corixien.viddaw.model.removeClip
import io.github.corixien.viddaw.model.splitClip
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
    data object Mixer : Panel
    data object Tempo : Panel
    data object Export : Panel
    data class TrackSettings(val trackId: Long) : Panel
    data class Effects(val trackId: Long) : Panel
    data class NoteEditor(val clipId: Long) : Panel
    data class Play(val trackId: Long) : Panel
}

/** State and actions of one open project. Main thread only. */
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
    var selectedClip by mutableStateOf<Long?>(null)
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

    /** Timeline view: width of one beat in dp and the tick at the left edge. */
    var beatWidth by mutableFloatStateOf(36f)
    var scrollTick by mutableDoubleStateOf(0.0)
    var snapOn by mutableStateOf(true)
    var viewWidthPx = 0f

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
        if (selectedClip?.let(p::clip) == null) selectedClip = null
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
        if (panel != Panel.None) {
            panel = Panel.None
            return true
        }
        if (selectedClip != null) {
            selectedClip = null
            return true
        }
        return false
    }

    /** Grid for moving and trimming, coarser when zoomed out. 0 when snapping is off. */
    fun gridTicks(): Long = when {
        !snapOn -> 0
        beatWidth >= 90f -> PPQ / 4
        beatWidth >= 28f -> PPQ
        else -> project.ticksPerBar
    }

    fun addTrack(type: TrackType, name: String) {
        edit { it.addTrack(type, name) }
        selectedTrack = project.tracks.last().id
    }

    fun selectClip(id: Long?) {
        selectedClip = id
        id?.let { project.trackOfClip(it) }?.let { selectedTrack = it.id }
    }

    fun splitSelected() {
        val id = selectedClip ?: return
        edit { it.splitClip(id, positionTick) }
    }

    fun deleteSelected() {
        val id = selectedClip ?: return
        selectedClip = null
        edit { it.removeClip(id) }
    }

    fun duplicateSelected() {
        val id = selectedClip ?: return
        val next = project.nextId
        edit { it.duplicateClip(id) }
        if (project.clip(next) != null) selectedClip = next
    }

    fun addMidiClip(track: Track, atTick: Long): Long {
        val bar = project.ticksPerBar
        val id = project.nextId
        val bars = if (track.type == TrackType.Drums) 1 else 4
        edit { it.addClip(track.id, Clip(0, (atTick / bar) * bar, length = bar * bars)) }
        selectClip(id)
        return id
    }

    // ---- transport ----

    /** Called once per display frame while the studio is visible. */
    fun tick() {
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

    fun seekTick(tick: Long) = engine.seek(ticksToFrames(tick.coerceAtLeast(0), project.bpm))

    fun toStart() {
        engine.seek(0)
        scrollTick = 0.0
    }

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

    /** Imports a video onto the selected video track, or a new one. Its sound becomes part of the clip. */
    fun importVideo() = scope.launch {
        val picked = platform.pickVideo() ?: return@launch
        val durationMs = platform.videoDurationMs(picked.uri)
        if (durationMs <= 0) {
            notice = T.importFailed
            return@launch
        }
        val audio = import(picked.uri, "vid")
        val frames = audio?.second ?: (durationMs * SAMPLE_RATE / 1000)
        val at = positionTick
        beginGesture()
        val target = selectedTrack?.let(project::track)?.takeIf { it.type == TrackType.Video }?.id ?: run {
            addTrack(TrackType.Video, "Video")
            project.tracks.last().id
        }
        val id = project.nextId
        edit {
            it.addClip(target, Clip(0, at, picked.name.substringBeforeLast('.'), audio?.first, 0, frames, frames, video = picked.uri))
        }
        endGesture()
        selectClip(id)
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
        val clips = project.tracks.filter { it.type == TrackType.Video && !it.mute }.flatMap { it.clips }.sortedBy { it.start }
        return clips.mapIndexedNotNull { i, c ->
            val start = ticksToFrames(c.start, bpm)
            val nextStart = clips.getOrNull(i + 1)?.let { ticksToFrames(it.start, bpm) } ?: Long.MAX_VALUE
            val frames = minOf(c.frames, nextStart - start)
            if (frames <= 0 || c.video == null) null
            else VideoSegment(c.video, start.toDouble() / SAMPLE_RATE, c.offset.toDouble() / SAMPLE_RATE, frames.toDouble() / SAMPLE_RATE)
        }
    }

    val hasVideo: Boolean get() = project.tracks.any { it.type == TrackType.Video }

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
            format.video -> platform.exportVideo(videoSegments(), wav, end.toDouble() / SAMPLE_RATE, project.name) { progress = 0.3f + it * 0.7f }
            else -> platform.exportAudio(wav, format, project.name)
        }
        platform.delete(wav)
        busy = null
        panel = Panel.None
        if (where != null) message = where else notice = T.exportFailed
    }
}
