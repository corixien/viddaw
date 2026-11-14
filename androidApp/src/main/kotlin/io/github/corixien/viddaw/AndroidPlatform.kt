package io.github.corixien.viddaw

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.corixien.viddaw.app.ExportFormat
import io.github.corixien.viddaw.app.PickedMedia
import io.github.corixien.viddaw.app.Platform
import io.github.corixien.viddaw.app.VideoSegment
import io.github.corixien.viddaw.engine.Native
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.util.Locale

/** Android implementation of the host services. Must be created before the activity starts. */
class AndroidPlatform(private val activity: ComponentActivity) : Platform {
    override val dataDir: String = activity.filesDir.absolutePath
    override val systemLanguage: String get() = Locale.getDefault().language

    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private var pendingPick: CompletableDeferred<Uri?>? = null
    private val permissionLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingPermission?.complete(it)
    }
    private val pickLauncher = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        pendingPick?.complete(it)
    }
    private val importLock = Mutex()
    val midi = MidiInput(activity) { pitch, velocity, on -> onMidiNote?.invoke(pitch, velocity, on) }
    val video = VideoSupport(activity)

    override var onMidiNote: ((Int, Int, Boolean) -> Unit)? = null

    override fun readText(path: String): String? = File(path).takeIf { it.isFile }?.readText()

    override fun writeText(path: String, text: String) {
        // Write beside the target and rename, so a crash never leaves half a project file.
        val target = File(path)
        target.parentFile?.mkdirs()
        val tmp = File("$path.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    override fun listDirs(path: String): List<String> = File(path).listFiles { f -> f.isDirectory }?.map { it.name } ?: emptyList()
    override fun delete(path: String) { File(path).deleteRecursively() }
    override fun mkdirs(path: String) { File(path).mkdirs() }
    override fun copy(from: String, to: String) { File(from).copyRecursively(File(to), overwrite = true) }
    override fun nowMillis(): Long = System.currentTimeMillis()

    override suspend fun requestMicPermission(): Boolean {
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        val result = CompletableDeferred<Boolean>()
        pendingPermission = result
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        return result.await()
    }

    private suspend fun pick(vararg mimes: String): PickedMedia? {
        val result = CompletableDeferred<Uri?>()
        pendingPick = result
        pickLauncher.launch(arrayOf(*mimes))
        val uri = result.await() ?: return null
        // Video stays where it is, so keep access across restarts.
        runCatching { activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        return PickedMedia(uri.toString(), name ?: "Clip")
    }

    override suspend fun pickAudio(): PickedMedia? = pick("audio/*", "video/*")
    override suspend fun pickVideo(): PickedMedia? = pick("video/*")

    override suspend fun importAudio(uri: String, destWav: String, onProgress: (Float) -> Unit): Long =
        importLock.withLock { withContext(Dispatchers.IO) { runCatching { decode(Uri.parse(uri), destWav, onProgress) }.getOrDefault(0L) } }

    private fun decode(uri: Uri, dest: String, onProgress: (Float) -> Unit): Long {
        val extractor = MediaExtractor()
        extractor.setDataSource(activity, uri, null)
        val trackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return 0
        val format = extractor.getTrackFormat(trackIndex)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        extractor.selectTrack(trackIndex)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var started = false
        var float = false
        var shorts = ShortArray(0)
        var lastProgress = 0f
        try {
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o < 0) continue
                val out = codec.getOutputFormat(o)
                val channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                if (!started) {
                    float = out.containsKey(MediaFormat.KEY_PCM_ENCODING) && out.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    if (!Native.importBegin(dest, out.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels)) return 0
                    started = true
                }
                if (info.size > 0) {
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset).limit(info.offset + info.size)
                    val samples = info.size / if (float) 4 else 2
                    if (shorts.size < samples) shorts = ShortArray(samples)
                    if (float) {
                        val f = buf.asFloatBuffer()
                        for (k in 0 until samples) shorts[k] = (f.get(k).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    } else {
                        buf.asShortBuffer().get(shorts, 0, samples)
                    }
                    Native.importWrite(shorts, samples / channels)
                    if (durationUs > 0) {
                        val p = info.presentationTimeUs.toFloat() / durationUs
                        if (p - lastProgress > 0.02f) {
                            lastProgress = p
                            onProgress(p)
                        }
                    }
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        return if (started) Native.importEnd() else 0
    }

    override suspend fun videoDurationMs(uri: String): Long = withContext(Dispatchers.IO) {
        runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(activity, Uri.parse(uri))
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            }
        }.getOrDefault(0L)
    }

    override suspend fun exportAudio(wavPath: String, format: ExportFormat, name: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val wav = File(wavPath)
            val out = File(activity.cacheDir, "export.${format.extension}")
            when (format) {
                ExportFormat.Wav -> wav.copyTo(out, overwrite = true)
                ExportFormat.Flac -> encode(wav, out, MediaFormat.MIMETYPE_AUDIO_FLAC, muxed = false)
                else -> encode(wav, out, MediaFormat.MIMETYPE_AUDIO_AAC, muxed = true)
            }
            val mime = when (format) {
                ExportFormat.Wav -> "audio/x-wav"
                ExportFormat.Flac -> "audio/flac"
                else -> "audio/mp4"
            }
            publish(out, name, format.extension, mime, video = false).also { out.delete() }
        }.getOrNull()
    }

    /** Encodes a 16-bit stereo WAV. AAC goes into an MP4 container, FLAC is written as a raw stream. */
    private fun encode(wav: File, out: File, mime: String, muxed: Boolean) {
        val format = MediaFormat.createAudioFormat(mime, 48000, 2).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            if (muxed) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
            } else {
                setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
            }
        }
        val codec = MediaCodec.createEncoderByType(mime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = if (muxed) MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) else null
        val raw = if (muxed) null else FileOutputStream(out)
        val input = wav.inputStream().buffered()
        input.skip(44)
        val info = MediaCodec.BufferInfo()
        val chunk = ByteArray(8192)
        var frames = 0L
        var inputDone = false
        var track = -1
        try {
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = input.read(chunk, 0, minOf(chunk.size, buf.capacity()) / 4 * 4)
                        val pts = frames * 1_000_000L / 48000
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(chunk, 0, n)
                            codec.queueInputBuffer(i, 0, n, pts, 0)
                            frames += n / 4
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && muxer != null) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                if (o < 0) continue
                val data = codec.getOutputBuffer(o)!!
                if (info.size > 0) {
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (muxer != null) {
                        if (!config && track >= 0) muxer.writeSampleData(track, data, info)
                    } else {
                        // The FLAC stream header arrives as codec config and belongs at the start of the file.
                        val bytes = ByteArray(info.size)
                        data.position(info.offset)
                        data.get(bytes)
                        raw!!.write(bytes)
                    }
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } finally {
            input.close()
            codec.stop()
            codec.release()
            raw?.close()
            if (track >= 0) muxer?.stop()
            muxer?.release()
        }
    }

    /** Puts a finished file into the shared Music or Movies folder. Returns the user-visible location. */
    fun publish(file: File, name: String, extension: String, mime: String, video: Boolean): String? {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Viddaw" }
        val folder = (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC) + "/Viddaw"
        if (Build.VERSION.SDK_INT < 29) {
            val dir = activity.getExternalFilesDir(if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC) ?: return null
            val target = File(dir, "$safe.$extension")
            file.copyTo(target, overwrite = true)
            return target.path
        }
        val resolver = activity.contentResolver
        val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$safe.$extension")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: return null
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return "$folder/$safe.$extension"
    }

    override suspend fun exportVideo(segments: List<VideoSegment>, wavPath: String, totalSec: Double, name: String, onProgress: (Float) -> Unit): String? {
        val out = File(activity.cacheDir, "export.mp4")
        if (!video.export(segments, wavPath, totalSec, out, onProgress)) return null
        return withContext(Dispatchers.IO) { publish(out, name, "mp4", "video/mp4", video = true).also { out.delete() } }
    }

    @Composable
    override fun VideoPreview(segments: List<VideoSegment>, positionSec: () -> Double, playing: Boolean, modifier: Modifier) {
        video.Preview(segments, positionSec, playing, modifier)
    }
}
