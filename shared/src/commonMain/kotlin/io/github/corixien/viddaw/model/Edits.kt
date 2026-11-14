package io.github.corixien.viddaw.model

/** Pure project transformations. Every edit returns a new Project, which makes undo a list of values. */

const val MIN_CLIP_TICKS = PPQ / 8
private const val MIN_CLIP_FRAMES = 2400L

fun Project.mapTrack(id: Long, f: (Track) -> Track): Project =
    copy(tracks = tracks.map { if (it.id == id) f(it) else it })

fun Project.mapClip(clipId: Long, f: (Clip) -> Clip): Project =
    copy(tracks = tracks.map { t ->
        if (t.clips.any { it.id == clipId }) t.copy(clips = t.clips.map { if (it.id == clipId) f(it) else it }) else t
    })

fun Project.addTrack(type: TrackType, name: String): Project {
    val color = tracks.size % TRACK_COLOR_COUNT
    return copy(tracks = tracks + Track(nextId, type, name, color), nextId = nextId + 1)
}

fun Project.removeTrack(id: Long): Project = copy(tracks = tracks.filter { it.id != id })

fun Project.moveTrack(id: Long, delta: Int): Project {
    val from = tracks.indexOfFirst { it.id == id }
    val to = (from + delta).coerceIn(0, tracks.lastIndex)
    if (from < 0 || from == to) return this
    val list = tracks.toMutableList()
    list.add(to, list.removeAt(from))
    return copy(tracks = list)
}

/** Adds a clip to a track; the clip's id is replaced by a fresh one. */
fun Project.addClip(trackId: Long, clip: Clip): Project =
    mapTrack(trackId) { it.copy(clips = it.clips + clip.copy(id = nextId)) }.copy(nextId = nextId + 1)

fun Project.removeClip(clipId: Long): Project =
    copy(tracks = tracks.map { t -> t.copy(clips = t.clips.filter { it.id != clipId }) })

fun Track.accepts(clip: Clip): Boolean = when (type) {
    TrackType.Audio -> clip.file != null && clip.video == null
    TrackType.Video -> clip.video != null
    else -> clip.isMidi
}

/** Moves a clip in time and, if the target track takes this kind of clip, across tracks. */
fun Project.moveClip(clipId: Long, newStart: Long, toTrackId: Long? = null): Project {
    val clip = clip(clipId) ?: return this
    val from = trackOfClip(clipId) ?: return this
    val moved = clip.copy(start = newStart.coerceAtLeast(0))
    val target = toTrackId?.let(::track)?.takeIf { it.id != from.id && it.accepts(clip) }
        ?: return mapClip(clipId) { moved }
    return copy(tracks = tracks.map { t ->
        when (t.id) {
            from.id -> t.copy(clips = t.clips.filter { it.id != clipId })
            target.id -> t.copy(clips = t.clips + moved)
            else -> t
        }
    })
}

/** Cuts a clip in two at [tick]. Nothing happens unless the cut is strictly inside the clip. */
fun Project.splitClip(clipId: Long, tick: Long): Project {
    val clip = clip(clipId) ?: return this
    val track = trackOfClip(clipId) ?: return this
    if (tick <= clip.start || tick >= clip.endTick(bpm)) return this
    val cut = tick - clip.start
    val (left, right) = if (clip.isMidi) {
        clip.copy(length = cut) to clip.copy(
            id = nextId, start = tick, length = clip.length - cut,
            notes = clip.notes.map { it.copy(start = it.start - cut) },
        )
    } else {
        val cutFrames = ticksToFrames(cut, bpm).coerceIn(1, clip.frames - 1)
        clip.copy(frames = cutFrames) to clip.copy(id = nextId, start = tick, offset = clip.offset + cutFrames, frames = clip.frames - cutFrames)
    }
    return mapTrack(track.id) { t -> t.copy(clips = t.clips.flatMap { if (it.id == clipId) listOf(left, right) else listOf(it) }) }
        .copy(nextId = nextId + 1)
}

/** Moves the left edge of a clip to [tick] without moving its content on the timeline. */
fun Project.trimClipStart(clipId: Long, tick: Long): Project = mapClip(clipId) { c ->
    if (c.isMidi) {
        val start = tick.coerceIn(0, c.start + c.length - MIN_CLIP_TICKS)
        val d = start - c.start
        c.copy(start = start, length = c.length - d, notes = c.notes.map { it.copy(start = it.start - d) })
    } else {
        val wanted = ticksToFrames(tick, bpm) - ticksToFrames(c.start, bpm)
        val d = wanted.coerceIn(-c.offset, c.frames - MIN_CLIP_FRAMES)
        c.copy(start = (c.start + framesToTicks(d, bpm)).coerceAtLeast(0), offset = c.offset + d, frames = c.frames - d)
    }
}

/** Moves the right edge of a clip to [tick]. Audio cannot grow past the end of its file. */
fun Project.trimClipEnd(clipId: Long, tick: Long): Project = mapClip(clipId) { c ->
    if (c.isMidi) {
        c.copy(length = (tick - c.start).coerceAtLeast(MIN_CLIP_TICKS))
    } else {
        val frames = ticksToFrames(tick - c.start, bpm).coerceIn(MIN_CLIP_FRAMES, (c.fileFrames - c.offset).coerceAtLeast(MIN_CLIP_FRAMES))
        c.copy(frames = frames)
    }
}

fun Project.duplicateClip(clipId: Long): Project {
    val clip = clip(clipId) ?: return this
    val track = trackOfClip(clipId) ?: return this
    return addClip(track.id, clip.copy(start = clip.endTick(bpm)))
}

fun Project.addEffect(trackId: Long, type: String): Project {
    val spec = effectSpec(type) ?: return this
    return mapTrack(trackId) { it.copy(effects = it.effects + EffectInstance(nextId, type, true, spec.defaults())) }.copy(nextId = nextId + 1)
}

fun Project.mapEffect(trackId: Long, effectId: Long, f: (EffectInstance) -> EffectInstance): Project =
    mapTrack(trackId) { t -> t.copy(effects = t.effects.map { if (it.id == effectId) f(it) else it }) }

fun Project.removeEffect(trackId: Long, effectId: Long): Project =
    mapTrack(trackId) { t -> t.copy(effects = t.effects.filter { it.id != effectId }) }

fun Project.moveEffect(trackId: Long, effectId: Long, delta: Int): Project = mapTrack(trackId) { t ->
    val from = t.effects.indexOfFirst { it.id == effectId }
    val to = (from + delta).coerceIn(0, t.effects.lastIndex)
    if (from < 0 || from == to) t else t.copy(effects = t.effects.toMutableList().apply { add(to, removeAt(from)) })
}

/** Snaps to the nearest multiple of [grid] ticks; a grid of 0 leaves the value alone. */
fun snap(tick: Long, grid: Long): Long = if (grid <= 0) tick else ((tick + grid / 2).floorDiv(grid)) * grid

const val TRACK_COLOR_COUNT = 8
