package io.github.corixien.viddaw.model

/** Pure project transformations. Every edit returns a new Project, which makes undo a list of values. */

const val MIN_CLIP_TICKS = PPQ / 8
private const val MIN_CLIP_FRAMES = 2400L

fun Project.mapTrack(id: Long, f: (Track) -> Track): Project =
    copy(tracks = tracks.map { if (it.id == id) f(it) else it })

fun Project.mapClip(clipId: Long, f: (Clip) -> Clip): Project = mapClips(setOf(clipId), f)

fun Project.mapClips(ids: Set<Long>, f: (Clip) -> Clip): Project =
    copy(tracks = tracks.map { t ->
        if (t.clips.any { it.id in ids }) t.copy(clips = t.clips.map { if (it.id in ids) f(it) else it }) else t
    })

fun Project.addTrack(type: TrackType, name: String, color: Int = tracks.size % TRACK_COLOR_COUNT): Project =
    copy(tracks = tracks + Track(nextId, type, name, color), nextId = nextId + 1)

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

fun Project.removeClip(clipId: Long): Project = removeClips(setOf(clipId))

fun Project.removeClips(ids: Set<Long>): Project =
    copy(tracks = tracks.map { t -> if (t.clips.any { it.id in ids }) t.copy(clips = t.clips.filter { it.id !in ids }) else t })

fun Track.accepts(clip: Clip): Boolean = when (type) {
    TrackType.Audio -> clip.isAudio
    TrackType.Video -> clip.video != null || clip.overlay != null
    else -> clip.isMidi
}

/** [ids] plus every clip that shares a group with one of them. */
fun Project.withGroups(ids: Set<Long>): Set<Long> {
    val groups = clips(ids).mapNotNull { it.group.takeIf { g -> g != 0L } }.toSet()
    if (groups.isEmpty()) return ids
    return ids + tracks.flatMap { t -> t.clips.filter { it.group in groups }.map { it.id } }
}

/** Puts the clips into one new group, or with [on] false releases them from their groups. */
fun Project.groupClips(ids: Set<Long>, on: Boolean): Project =
    if (on) mapClips(ids) { it.copy(group = nextId) }.copy(nextId = nextId + 1) else mapClips(ids) { it.copy(group = 0) }

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

/** Shifts several clips by the same amount; the earliest one stops at the project start. */
fun Project.moveClips(ids: Set<Long>, delta: Long): Project {
    val first = clips(ids).minOfOrNull { it.start } ?: return this
    val d = delta.coerceAtLeast(-first)
    return if (d == 0L) this else mapClips(ids) { it.copy(start = it.start + d) }
}

private fun Project.splitOne(clip: Clip, tick: Long, rightId: Long, rightGroup: Long): List<Clip> {
    if (tick <= clip.start || tick >= clip.endTick(bpm)) return listOf(clip)
    val cut = tick - clip.start
    return if (clip.tickBased) {
        listOf(
            clip.copy(length = cut),
            clip.copy(id = rightId, start = tick, length = clip.length - cut, notes = clip.notes.map { it.copy(start = it.start - cut) }, group = rightGroup),
        )
    } else {
        val cutFrames = ticksToFrames(cut, bpm).coerceIn(1, clip.frames - 1)
        listOf(
            clip.copy(frames = cutFrames, fadeOut = 0, fx = clip.fx.copy(transOut = 0)),
            clip.copy(
                id = rightId, start = tick, offset = clip.offset + cutFrames, frames = clip.frames - cutFrames,
                fadeIn = 0, fx = clip.fx.copy(transIn = 0), group = rightGroup,
            ),
        )
    }
}

/** Cuts a clip in two at [tick]. Nothing happens unless the cut is strictly inside the clip. */
fun Project.splitClip(clipId: Long, tick: Long): Project = splitClips(setOf(clipId), tick)

/** Cuts every given clip that [tick] falls inside. The right halves of a group form a new group. */
fun Project.splitClips(ids: Set<Long>, tick: Long): Project {
    var next = nextId
    val newGroups = HashMap<Long, Long>()
    val out = tracks.map { t ->
        if (t.clips.none { it.id in ids }) t else t.copy(clips = t.clips.flatMap { c ->
            if (c.id !in ids || tick <= c.start || tick >= c.endTick(bpm)) listOf(c)
            else {
                val group = if (c.group == 0L) 0L else newGroups.getOrPut(c.group) { next++ }
                splitOne(c, tick, next++, group)
            }
        })
    }
    return if (next == nextId) this else copy(tracks = out, nextId = next)
}

/** Moves the left edge of a clip to [tick] without moving its content on the timeline. */
fun Project.trimClipStart(clipId: Long, tick: Long): Project = trimClipsStart(setOf(clipId), tick)

fun Project.trimClipsStart(ids: Set<Long>, tick: Long): Project = mapClips(ids) { c ->
    if (c.tickBased) {
        val start = tick.coerceIn(0, c.start + c.length - MIN_CLIP_TICKS)
        val d = start - c.start
        c.copy(start = start, length = c.length - d, notes = c.notes.map { it.copy(start = it.start - d) })
    } else {
        val wanted = ticksToFrames(tick, bpm) - ticksToFrames(c.start, bpm)
        val d = wanted.coerceIn(-c.offset, c.frames - MIN_CLIP_FRAMES)
        c.copy(start = (c.start + framesToTicks(d, bpm)).coerceAtLeast(0), offset = c.offset + d, frames = c.frames - d)
    }
}

/** Moves the right edge of a clip to [tick]. Audio and video cannot grow past the end of their source. */
fun Project.trimClipEnd(clipId: Long, tick: Long): Project = trimClipsEnd(setOf(clipId), tick)

fun Project.trimClipsEnd(ids: Set<Long>, tick: Long): Project = mapClips(ids) { c ->
    if (c.tickBased) {
        c.copy(length = (tick - c.start).coerceAtLeast(MIN_CLIP_TICKS))
    } else {
        val frames = ticksToFrames(tick - c.start, bpm).coerceIn(MIN_CLIP_FRAMES, (c.fileFrames - c.offset).coerceAtLeast(MIN_CLIP_FRAMES))
        c.copy(frames = frames)
    }
}

/**
 * Inserts copies of [clips], given with the track each came from, shifted by [delta] ticks.
 * A copy goes to its own track if that still exists and takes it, else to [fallbackTrack].
 * Groups among the copies are kept but made distinct from the originals. New ids start at the old nextId.
 */
fun Project.insertCopies(clips: List<Pair<Long, Clip>>, delta: Long, fallbackTrack: Long? = null): Project {
    var next = nextId
    val groups = HashMap<Long, Long>()
    for ((_, c) in clips) if (c.group != 0L) groups.getOrPut(c.group) { next++ }
    val added = HashMap<Long, MutableList<Clip>>()
    for ((trackId, c) in clips) {
        val target = track(trackId)?.takeIf { it.accepts(c) } ?: fallbackTrack?.let(::track)?.takeIf { it.accepts(c) } ?: continue
        added.getOrPut(target.id) { ArrayList() } += c.copy(id = next++, start = (c.start + delta).coerceAtLeast(0), group = groups[c.group] ?: 0)
    }
    if (added.isEmpty()) return this
    return copy(tracks = tracks.map { t -> added[t.id]?.let { t.copy(clips = t.clips + it) } ?: t }, nextId = next)
}

/** Places a copy of the clips directly after the last of them. */
fun Project.duplicateClips(ids: Set<Long>): Project {
    val picked = tracks.flatMap { t -> t.clips.filter { it.id in ids }.map { t.id to it } }
    if (picked.isEmpty()) return this
    val span = picked.maxOf { it.second.endTick(bpm) } - picked.minOf { it.second.start }
    return insertCopies(picked, span)
}

fun Project.duplicateClip(clipId: Long): Project = duplicateClips(setOf(clipId))

/** Scales a MIDI clip in time; [speed] 2 plays it twice as fast. */
fun Clip.stretchedNotes(speed: Float): Clip = copy(
    length = (length / speed).toLong().coerceAtLeast(MIN_CLIP_TICKS),
    notes = notes.map { it.copy(start = (it.start / speed).toLong(), length = (it.length / speed).toLong().coerceAtLeast(1)) },
)

fun Clip.transposedNotes(semitones: Int): Clip = copy(notes = notes.map { it.copy(pitch = (it.pitch + semitones).coerceIn(0, 127)) })

fun Project.addEffect(trackId: Long, type: String): Project {
    val spec = effectSpec(type) ?: return this
    return mapTrack(trackId) { it.copy(effects = it.effects + EffectInstance(nextId, type, true, spec.defaults())) }.copy(nextId = nextId + 1)
}

fun Project.mapEffect(trackId: Long, effectId: Long, f: (EffectInstance) -> EffectInstance): Project =
    mapTrack(trackId) { t -> t.copy(effects = t.effects.map { if (it.id == effectId) f(it) else it }) }

fun Project.removeEffect(trackId: Long, effectId: Long): Project =
    mapTrack(trackId) { t ->
        t.copy(effects = t.effects.filter { it.id != effectId }, lanes = t.lanes.filter { !it.target.startsWith("fx:$effectId:") })
    }

fun Project.moveEffect(trackId: Long, effectId: Long, delta: Int): Project = mapTrack(trackId) { t ->
    val from = t.effects.indexOfFirst { it.id == effectId }
    val to = (from + delta).coerceIn(0, t.effects.lastIndex)
    if (from < 0 || from == to) t else t.copy(effects = t.effects.toMutableList().apply { add(to, removeAt(from)) })
}

/** Snaps to the nearest multiple of [grid] ticks; a grid of 0 leaves the value alone. */
fun snap(tick: Long, grid: Long): Long = if (grid <= 0) tick else ((tick + grid / 2).floorDiv(grid)) * grid

const val TRACK_COLOR_COUNT = 8
