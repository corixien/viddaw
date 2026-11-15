package io.github.corixien.viddaw

import io.github.corixien.viddaw.app.AppJson
import io.github.corixien.viddaw.engine.engineSnapshot
import io.github.corixien.viddaw.model.Clip
import io.github.corixien.viddaw.model.EFFECT_SPECS
import io.github.corixien.viddaw.model.Note
import io.github.corixien.viddaw.model.PPQ
import io.github.corixien.viddaw.model.Project
import io.github.corixien.viddaw.model.TrackType
import io.github.corixien.viddaw.model.addClip
import io.github.corixien.viddaw.model.addEffect
import io.github.corixien.viddaw.model.addTrack
import io.github.corixien.viddaw.model.clip
import io.github.corixien.viddaw.model.duplicateClip
import io.github.corixien.viddaw.model.endTick
import io.github.corixien.viddaw.model.framesToTicks
import io.github.corixien.viddaw.model.mapTrack
import io.github.corixien.viddaw.model.moveClip
import io.github.corixien.viddaw.model.moveTrack
import io.github.corixien.viddaw.model.snap
import io.github.corixien.viddaw.model.splitClip
import io.github.corixien.viddaw.model.ticksToFrames
import io.github.corixien.viddaw.model.trackOfClip
import io.github.corixien.viddaw.model.trimClipEnd
import io.github.corixien.viddaw.model.trimClipStart
import io.github.corixien.viddaw.ui.dbToFader
import io.github.corixien.viddaw.ui.faderToDb
import io.github.corixien.viddaw.ui.fmt
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelTest {
    // 120 BPM: one beat is 24000 frames and 960 ticks.
    private val base = Project("p", "Test").addTrack(TrackType.Audio, "A").addTrack(TrackType.Sampler, "S").addTrack(TrackType.Drums, "D")
    private val audioTrack = base.tracks[0].id
    private val samplerTrack = base.tracks[1].id
    private val audio = Clip(0, PPQ, file = "audio/a.wav", offset = 1000, frames = 96000, fileFrames = 200000)
    private val midi = Clip(0, 0, length = PPQ * 4, notes = listOf(Note(0, 240, 60), Note(PPQ * 2, 240, 64)))

    @Test
    fun timeConversionRoundTrips() {
        assertEquals(24000, ticksToFrames(PPQ, 120.0))
        assertEquals(PPQ, framesToTicks(24000, 120.0))
        assertEquals(48000, ticksToFrames(PPQ, 60.0))
        assertEquals(480, snap(500, 240))
        assertEquals(500, snap(500, 0))
    }

    @Test
    fun splitAudioKeepsContentInPlace() {
        val p = base.addClip(audioTrack, audio)
        val id = p.tracks[0].clips[0].id
        val s = p.splitClip(id, PPQ * 2)
        val (left, right) = s.tracks[0].clips
        assertEquals(24000, left.frames)
        assertEquals(PPQ * 2, right.start)
        assertEquals(1000 + 24000, right.offset)
        assertEquals(96000 - 24000, right.frames)
        assertTrue(left.id != right.id)
        // A cut outside the clip changes nothing.
        assertEquals(p, p.splitClip(id, 0))
        assertEquals(p, p.splitClip(id, PPQ * 100))
    }

    @Test
    fun splitMidiShiftsNotesOfTheRightPart() {
        val p = base.addClip(samplerTrack, midi)
        val s = p.splitClip(p.tracks[1].clips[0].id, PPQ * 2)
        val (left, right) = s.tracks[1].clips
        assertEquals(PPQ * 2, left.length)
        assertEquals(PPQ * 2, right.length)
        assertEquals(0, right.notes[1].start)
    }

    @Test
    fun trimIsBoundedByTheFile() {
        val p = base.addClip(audioTrack, audio)
        val id = p.tracks[0].clips[0].id
        // Pulling the start left can only reveal the 1000 frames before the offset.
        val early = p.trimClipStart(id, 0).clip(id)!!
        assertEquals(0, early.offset)
        assertEquals(97000, early.frames)
        // Pulling the end right stops at the end of the file.
        val late = p.trimClipEnd(id, PPQ * 1000).clip(id)!!
        assertEquals(199000, late.frames)
        val shorter = p.trimClipStart(id, PPQ * 2).clip(id)!!
        assertEquals(PPQ * 2, shorter.start)
        assertEquals(25000, shorter.offset)
        assertEquals(72000, shorter.frames)
    }

    @Test
    fun moveRespectsTrackKinds() {
        val p = base.addClip(audioTrack, audio)
        val id = p.tracks[0].clips[0].id
        // An audio clip cannot land on an instrument track; it only moves in time.
        val moved = p.moveClip(id, PPQ * 8, samplerTrack)
        assertEquals(audioTrack, moved.trackOfClip(id)?.id)
        assertEquals(PPQ * 8, moved.clip(id)?.start)
        assertEquals(0, p.moveClip(id, -50).clip(id)?.start)

        val m = base.addClip(samplerTrack, midi)
        val mid = m.tracks[1].clips[0].id
        assertEquals(m.tracks[2].id, m.moveClip(mid, 0, m.tracks[2].id).trackOfClip(mid)?.id)
    }

    @Test
    fun duplicatePlacesCopyAfterOriginal() {
        val p = base.addClip(audioTrack, audio)
        val d = p.duplicateClip(p.tracks[0].clips[0].id)
        assertEquals(2, d.tracks[0].clips.size)
        assertEquals(PPQ + framesToTicks(96000, 120.0), d.tracks[0].clips[1].start)
        assertEquals(d.tracks[0].clips[1].start + PPQ * 4, d.endTick())
    }

    @Test
    fun trackOrderAndIdsStayUnique() {
        val p = base.moveTrack(base.tracks[2].id, -2)
        assertEquals(listOf("D", "A", "S"), p.tracks.map { it.name })
        val ids = p.addClip(audioTrack, audio).addEffect(audioTrack, "eq").let { q -> q.tracks.map { it.id } + q.tracks.flatMap { t -> t.clips.map { it.id } + t.effects.map { it.id } } }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun snapshotCarriesTempoSoloAndNotes() {
        var p = base.addClip(audioTrack, audio).addClip(samplerTrack, midi).addEffect(audioTrack, "comp")
        p = p.mapTrack(samplerTrack) { it.copy(solo = true) }.copy(loopEnabled = true, loopStart = PPQ, loopEnd = PPQ * 5)
        val lines = engineSnapshot(p, "/dir").lines()
        assertEquals("P 24000.0 4 1 24000 120000 1.0 0", lines[0])
        // Solo on the sampler silences the other tracks.
        assertTrue(lines.any { it.startsWith("T $audioTrack 0 1.0 0.0 0") })
        assertTrue(lines.any { it.startsWith("T $samplerTrack 1 1.0 0.0 1") })
        assertTrue("C 24000 1000 96000 1.0 /dir/audio/a.wav" in lines)
        assertTrue("N 0 6000 60 100" in lines)
        assertTrue("N 48000 6000 64 100" in lines)
        assertTrue(lines.any { it.startsWith("E ") && " comp 1 7 " in it })
        assertEquals(16, lines.count { it.startsWith("D ") })
    }

    @Test
    fun snapshotDropsNotesOutsideTheClip() {
        val clip = midi.copy(notes = listOf(Note(-10, 100, 60), Note(PPQ * 4, 100, 61), Note(PPQ * 4 - 100, 500, 62)))
        val lines = engineSnapshot(base.addClip(samplerTrack, clip), "/d").lines().filter { it.startsWith("N ") }
        // Only the last note plays, cut at the clip end.
        assertEquals(listOf("N ${ticksToFrames(PPQ * 4 - 100, 120.0)} 2500 62 100"), lines)
    }

    @Test
    fun effectParametersMapBothWays() {
        for (spec in EFFECT_SPECS) {
            assertTrue(spec.params.size <= 16, spec.type)
            for (param in spec.params) {
                assertTrue(param.default in param.min..param.max, "${spec.type}.${param.name}")
                val back = param.fromNormalized(param.toNormalized(param.default))
                assertTrue(abs(back - param.default) <= abs(param.default) * 1e-3f + 1e-3f, "${spec.type}.${param.name}")
            }
        }
    }

    @Test
    fun faderAndFormatting() {
        assertEquals(0f, faderToDb(0.75f))
        assertEquals(6f, faderToDb(1f))
        assertEquals(-60f, faderToDb(0f))
        for (db in listOf(-40f, -12f, 0f, 3f)) assertTrue(abs(faderToDb(dbToFader(db)) - db) < 0.01f)
        assertEquals("-3.5", fmt(-3.5f))
        assertEquals("120", fmt(120.4f, 0))
        assertEquals("0.05", fmt(0.05f, 2))
    }

    @Test
    fun projectSurvivesSerialization() {
        val p = base.addClip(audioTrack, audio).addClip(samplerTrack, midi).addEffect(audioTrack, "reverb")
        val restored = AppJson.decodeFromString<Project>(AppJson.encodeToString(p))
        assertEquals(p, restored)
        assertNotNull(restored.tracks[2].pads.getOrNull(15))
    }
}
