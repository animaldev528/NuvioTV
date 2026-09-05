package com.nuvio.tv.ui.screens.player

import android.util.Log
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * TEMPORARY debug capture for the 5.1 private-listening buzz. NOT for merge.
 *
 * While [dir] is set, two streams are recorded to <dir>/cap.pcm:
 *   - RAW decoder PCM: every multichannel (>= 6 ch) buffer that reaches the private-listening tee
 *     is copied (one memcpy on the audio thread) — the exact buffers the fold reads, independent
 *     of whether a phone fork is armed (so force-PCM playback via fork/BT/>1x records it). Records
 *     carry channelCount 6/8.
 *   - DOWNMIX output: the stereo PCM16 the fork sender unicasts to the phone (channelCount 2).
 * Records are distinguished by channelCount (genuine 2.0 source is never captured: the sink only
 * offers >= 6 ch).
 *
 * Record framing (all int32 big-endian), so a reader can split and FFT per channel:
 *   byteLen int32 | channelCount int32 | sampleRate int32 | encoding int32 (0=int16,1=float) |
 *   byteLen raw interleaved PCM
 *
 * Capture stops after [MAX_RECORDS] (~30 s at a 2k-frame cadence) or once the file exceeds
 * [MAX_BYTES], whichever first. A full queue drops records rather than stalling the audio thread.
 */
internal object PlPcmCapture {
    private const val TAG = "PlPcmCapture"
    private const val MAX_RECORDS = 4096
    private const val MAX_BYTES = 200L * 1024 * 1024
    private const val QUEUE_CAPACITY = 256

    @Volatile
    var dir: File? = null
        set(value) {
            field = value
            if (value != null && !running) startWriter()
        }

    private class Rec(
        val bytes: ByteArray,
        val channelCount: Int,
        val sampleRate: Int,
        val isFloat: Boolean,
    )

    private val queue = ArrayBlockingQueue<Rec>(QUEUE_CAPACITY)
    private val writerRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var recordCount = 0
    @Volatile private var running = false
    private var dropped = 0

    /** Call on the audio thread. Copies remaining bytes; never blocks. */
    fun offer(source: ByteBuffer, channelCount: Int, sampleRate: Int, isFloat: Boolean) {
        val d = dir ?: return
        if (recordCount >= MAX_RECORDS) return
        val n = source.remaining()
        if (n <= 0) return
        val bytes = ByteArray(n)
        source.duplicate().get(bytes)
        if (!queue.offer(Rec(bytes, channelCount, sampleRate, isFloat))) {
            dropped++
        }
    }

    private fun startWriter() {
        if (!writerRunning.compareAndSet(false, true)) return
        running = true
        Thread({ runWriter() }, "nuvio-pl-capture-writer").apply {
            isDaemon = true
            start()
        }
    }

    private fun runWriter() {
        var out: DataOutputStream? = null
        var fileBytes = 0L
        try {
            val target = File(dir, "cap.pcm")
            target.parentFile?.mkdirs()
            out = DataOutputStream(BufferedOutputStream(FileOutputStream(target, false)))
            Log.i(TAG, "capturing multichannel PCM to ${target.absolutePath}")
            while (running) {
                val rec = try {
                    queue.poll(200, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    null
                }
                if (rec == null) continue
                if (recordCount >= MAX_RECORDS) break
                if (fileBytes + rec.bytes.size > MAX_BYTES) break
                out.writeInt(rec.bytes.size)
                out.writeInt(rec.channelCount)
                out.writeInt(rec.sampleRate)
                out.writeInt(if (rec.isFloat) 1 else 0)
                out.write(rec.bytes)
                fileBytes += rec.bytes.size + 16
                recordCount++
                if (recordCount % 200 == 0) {
                    Log.i(TAG, "captured $recordCount records (${fileBytes / 1024} KiB)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture writer failed", e)
        } finally {
            runCatching { out?.flush() }
            runCatching { out?.close() }
            running = false
            if (dropped > 0) Log.i(TAG, "capture done; $dropped records dropped under backpressure")
        }
    }
}
