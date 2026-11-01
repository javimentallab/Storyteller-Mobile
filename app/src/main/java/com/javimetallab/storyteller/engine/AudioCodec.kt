package com.javimetallab.storyteller.engine

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Mono 16-bit samples at some rate. */
class Pcm(val samples: ShortArray, val rate: Int) {
    val seconds get() = samples.size.toDouble() / rate

    fun resampledTo(target: Int): Pcm {
        if (target == rate || samples.isEmpty()) return this
        val n = (samples.size.toLong() * target / rate).toInt()
        val out = ShortArray(n)
        val step = rate.toDouble() / target
        for (i in 0 until n) {
            val p = i * step
            val a = p.toInt().coerceAtMost(samples.size - 1)
            val b = (a + 1).coerceAtMost(samples.size - 1)
            val f = p - a
            out[i] = (samples[a] * (1 - f) + samples[b] * f).toInt().toShort()
        }
        return Pcm(out, target)
    }
}

/** A WAV that grows. The header is kept true, so it can be played while it is being written. */
class WavWriter(private val file: File, val rate: Int, keepFrames: Long = 0) {
    private val raf = RandomAccessFile(file, "rw")
    var frames: Long = keepFrames
        private set

    init {
        raf.setLength(HEADER + frames * 2)     // resuming: what wasn't confirmed is cut off
        writeHeader()
        raf.seek(HEADER + frames * 2)
    }

    fun append(samples: ShortArray) {
        val b = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.asShortBuffer().put(samples)
        raf.write(b.array())
        frames += samples.size
    }

    fun silence(seconds: Double) = append(ShortArray((seconds * rate).toInt()))

    fun writeHeader() {
        val data = frames * 2
        val h = ByteBuffer.allocate(HEADER.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + data).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        h.putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(data.toInt())
        val pos = raf.filePointer
        raf.seek(0)
        raf.write(h.array())
        raf.seek(pos)
    }

    fun close() {
        writeHeader()
        raf.close()
    }

    companion object {
        const val HEADER = 44L
    }
}

/** Decoding with the phone's own codecs: no ffmpeg in the app, and hardly any size. */
object AudioCodec {
    private const val TIMEOUT_US = 10_000L

    private fun audioTrack(ex: MediaExtractor): Int {
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return i
        }
        throw IOException("no audio track")
    }

    /** Duration of an audio file, in seconds. 0 if the file doesn't say. */
    fun durationOf(file: File): Double {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.path)
            val f = ex.getTrackFormat(audioTrack(ex))
            return if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1e6 else 0.0
        } finally {
            ex.release()
        }
    }

    /**
     * Decodes in order from `startSeconds`, handing out mono pieces. `onPiece` returning false stops
     * early. Whatever the rate or the channels of the file, the piece says its own rate.
     */
    fun decode(file: File, startSeconds: Double = 0.0, onPiece: (ShortArray, Int) -> Boolean) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(file.path)
            val track = audioTrack(ex)
            ex.selectTrack(track)
            if (startSeconds > 0) ex.seekTo((startSeconds * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val format = ex.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floats = false
            val skipUntilUs = (startSeconds * 1e6).toLong()
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floats = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    var keepGoing = true
                    if (info.size > 0 && info.presentationTimeUs >= skipUntilUs) {
                        val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset).limit(info.offset + info.size)
                        keepGoing = onPiece(toMono(buf, channels, floats), rate)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(o, false)
                    if (eos || !keepGoing) break
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    private fun toMono(buf: ByteBuffer, channels: Int, floats: Boolean): ShortArray {
        val c = channels.coerceAtLeast(1)
        if (floats) {
            val f = buf.asFloatBuffer()
            val out = ShortArray(f.remaining() / c)
            for (i in out.indices) {
                var sum = 0f
                for (k in 0 until c) sum += f.get()
                out[i] = ((sum / c).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
            return out
        }
        val s = buf.asShortBuffer()
        val out = ShortArray(s.remaining() / c)
        for (i in out.indices) {
            var sum = 0
            for (k in 0 until c) sum += s.get()
            out[i] = (sum / c).toShort()
        }
        return out
    }

    /** A whole short file, one sentence. */
    fun decodeAll(file: File): Pcm {
        val parts = ArrayList<ShortArray>()
        var rate = 0
        decode(file) { piece, r ->
            parts += piece
            rate = r
            true
        }
        if (rate == 0) throw IOException("no audio")
        val all = ShortArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            p.copyInto(all, at)
            at += p.size
        }
        return Pcm(all, rate)
    }

    /** Compresses a finished chapter. AAC keeps seeking exact, and is a quarter of the size. */
    fun wavToM4a(wav: File, dest: File, rate: Int, bitrate: Int = 64_000) {
        val tmp = File(dest.path + ".tmp")
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            RandomAccessFile(wav, "r").use { input ->
                input.seek(WavWriter.HEADER)
                val total = wav.length() - WavWriter.HEADER
                var read = 0L
                var track = -1
                var inputDone = false
                val info = MediaCodec.BufferInfo()
                val chunk = ByteArray(8 * 1024)
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val want = minOf(buf.remaining().toLong(), chunk.size.toLong(), total - read).toInt()
                            val n = if (want > 0) input.read(chunk, 0, want) else -1
                            val pts = read / 2 * 1_000_000L / rate
                            if (n <= 0) {
                                codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                buf.put(chunk, 0, n)
                                codec.queueInputBuffer(i, 0, n, pts, 0)
                                read += n
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        started = true
                    } else if (o >= 0) {
                        val buf = codec.getOutputBuffer(o)!!
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (info.size > 0 && !config && started) {
                            buf.position(info.offset).limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(o, false)
                        if (eos) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { if (started) muxer.stop() }
            runCatching { muxer.release() }
        }
        if (!tmp.renameTo(dest)) throw IOException("rename m4a")
    }

    /** A complete WAV for a piece of audio being sent to a transcription model. */
    fun writeWav(file: File, samples: ShortArray, count: Int, rate: Int) {
        val w = WavWriter(file, rate)
        w.append(samples.copyOf(count))
        w.close()
    }
}
