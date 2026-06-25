package dev.dean.voice.audio

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.PowerManager
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.math.sqrt

/**
 * Captures 16kHz mono PCM — exactly what Whisper's feature extractor expects.
 *
 * Usage:
 *   val recorder = AudioRecorder(context)
 *   recorder.startRecording().collect { chunk -> /* PCM chunk arrived */ }
 *   // Flow completes automatically on silence or max duration.
 *   // To stop manually: recorder.stopRecording() then cancel the collection job.
 *   val fullPcm = recorder.getRecordedAudio()
 */
class AudioRecorder(
    private val context: Context,
    private val silenceThresholdMs: Int = 2_000,
    private val maxDurationSec: Int = 60,
) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // RMS below this is considered silence. Tune empirically on device.
        private const val SILENCE_RMS_THRESHOLD = 300

        private const val WAKE_LOCK_TAG = "dev.dean.voice:recording"
    }

    @Volatile private var isRecording = false
    private var audioRecord: AudioRecord? = null

    // Chunks accumulated during the current recording session.
    // Guarded by `chunks` lock — written on IO thread, read on caller thread.
    private val chunks = mutableListOf<ShortArray>()

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecording(): Flow<ShortArray> = flow {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        // Buffer at least 100 ms of audio (SAMPLE_RATE / 10 samples × 2 bytes each).
        val bufBytes = maxOf(minBuf, SAMPLE_RATE / 10 * 2)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufBytes,
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialize — check RECORD_AUDIO permission."
        }

        // Acquire a partial wake lock so the CPU stays alive for the full recording window.
        // The timeout is maxDurationSec + 30 s — it auto-releases as a battery safeguard in
        // case the finally block is somehow never reached (e.g. process kill).
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        wakeLock.acquire((maxDurationSec + 30) * 1_000L)

        audioRecord = recorder
        synchronized(chunks) { chunks.clear() }
        recorder.startRecording()
        isRecording = true

        val buffer = ShortArray(bufBytes / 2)
        val maxSamples = maxDurationSec * SAMPLE_RATE
        val silenceThresholdSamples = (silenceThresholdMs / 1000.0 * SAMPLE_RATE).toInt()
        var silentSamples = 0
        var totalSamples = 0

        try {
            while (isRecording) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                val chunk = buffer.copyOf(read)
                synchronized(chunks) { chunks.add(chunk) }
                emit(chunk)

                totalSamples += read

                val rms = rms(chunk, read)
                if (rms < SILENCE_RMS_THRESHOLD) {
                    silentSamples += read
                    if (silentSamples >= silenceThresholdSamples) {
                        isRecording = false
                    }
                } else {
                    silentSamples = 0
                }

                if (totalSamples >= maxSamples) {
                    isRecording = false
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
            audioRecord = null
            isRecording = false
            if (wakeLock.isHeld) wakeLock.release()
        }
    }.flowOn(Dispatchers.IO)

    /** Signal a manual stop. The Flow will complete on the next read cycle. */
    fun stopRecording() {
        isRecording = false
    }

    /** Returns the full accumulated PCM buffer from the last recording session. */
    fun getRecordedAudio(): ShortArray {
        val snapshot = synchronized(chunks) { chunks.toList() }
        val total = snapshot.sumOf { it.size }
        val result = ShortArray(total)
        var offset = 0
        for (c in snapshot) {
            c.copyInto(result, offset)
            offset += c.size
        }
        return result
    }

    fun isRecording(): Boolean = isRecording

    fun release() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun rms(samples: ShortArray, count: Int): Int {
        var sumSq = 0L
        for (i in 0 until count) {
            val s = samples[i].toLong()
            sumSq += s * s
        }
        return sqrt(sumSq.toDouble() / count).toInt()
    }
}
