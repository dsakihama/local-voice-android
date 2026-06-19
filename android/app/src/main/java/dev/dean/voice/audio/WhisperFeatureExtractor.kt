package dev.dean.voice.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Converts raw 16kHz PCM into an 80-bin log-mel spectrogram [80 × 3000]
 * matching Whisper's preprocessing exactly.
 *
 * Parameters mirror openai/whisper (whisper/audio.py):
 *   n_fft=400, hop_length=160, n_mels=80, chunk_length=30s → 3000 frames.
 *
 * The FFT is zero-padded to the next power of two (512) so a radix-2
 * Cooley-Tukey FFT can be used. The mel filterbank is built for the
 * resulting bin frequencies (512-pt, 0–8 kHz), which is accurate enough
 * for Whisper's mel-scale feature space.
 */
class WhisperFeatureExtractor {

    companion object {
        const val N_MELS     = 80
        const val N_FFT      = 400
        const val HOP_LENGTH = 160
        const val N_FRAMES   = 3_000   // 30 s × (16000/160)
        const val FFT_SIZE   = 512     // next power-of-2 ≥ N_FFT

        private const val SAMPLE_RATE = 16_000
        private const val F_MIN       = 0.0
        private const val F_MAX       = SAMPLE_RATE / 2.0
    }

    // Hann window over N_FFT samples
    private val hannWindow = FloatArray(N_FFT) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / N_FFT))).toFloat()
    }

    // Mel filterbank: N_MELS × (FFT_SIZE/2+1) triangular filters
    private val melFilterbank: Array<FloatArray> = buildMelFilterbank()

    /**
     * Converts a raw PCM buffer (16-bit samples) into a log-mel spectrogram.
     * Returns a FloatArray of shape [N_MELS × N_FRAMES] in row-major order
     * (mel_bin varies slowest), ready to reshape into ORT input [1, 80, 3000].
     */
    fun extract(pcm: ShortArray): FloatArray {
        // Normalize to [-1, 1]
        val samples = FloatArray(pcm.size) { pcm[it] / 32768f }

        // Pad/truncate to exactly cover N_FRAMES hops
        val requiredLength = N_FRAMES * HOP_LENGTH + N_FFT
        val padded = when {
            samples.size >= requiredLength -> samples
            else -> samples.copyOf(requiredLength)  // zero-pad
        }

        val nBins = FFT_SIZE / 2 + 1
        val result = FloatArray(N_MELS * N_FRAMES)
        val fftReal = FloatArray(FFT_SIZE)
        val fftImag = FloatArray(FFT_SIZE)
        val powerSpec = FloatArray(nBins)

        for (frame in 0 until N_FRAMES) {
            val start = frame * HOP_LENGTH

            // Windowed frame → FFT buffer (zero-padded to FFT_SIZE)
            fftReal.fill(0f)
            fftImag.fill(0f)
            for (i in 0 until N_FFT) {
                val sampleIdx = start + i
                fftReal[i] = if (sampleIdx < padded.size) padded[sampleIdx] * hannWindow[i] else 0f
            }

            fft(fftReal, fftImag)

            // Power spectrum for positive frequencies
            for (i in 0 until nBins) {
                powerSpec[i] = fftReal[i] * fftReal[i] + fftImag[i] * fftImag[i]
            }

            // Apply mel filterbank and log
            for (mel in 0 until N_MELS) {
                var energy = 0f
                val filter = melFilterbank[mel]
                for (bin in 0 until nBins) {
                    energy += filter[bin] * powerSpec[bin]
                }
                result[mel * N_FRAMES + frame] = ln(maxOf(energy, 1e-10f))
            }
        }

        // Whisper normalization: scale so max is 1.0, clip to [-1, 1]
        var maxVal = Float.NEGATIVE_INFINITY
        for (v in result) if (v > maxVal) maxVal = v
        for (i in result.indices) {
            result[i] = ((result[i] - maxVal) / 4f + 1f).coerceIn(-1f, 1f)
        }

        return result
    }

    // ── Mel filterbank ──────────────────────────────────────────────────

    private fun buildMelFilterbank(): Array<FloatArray> {
        fun hzToMel(hz: Double) = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

        val melMin = hzToMel(F_MIN)
        val melMax = hzToMel(F_MAX)

        // N_MELS + 2 evenly-spaced mel points → converted to Hz
        val melPoints = DoubleArray(N_MELS + 2) { i ->
            melToHz(melMin + i * (melMax - melMin) / (N_MELS + 1))
        }

        val nBins = FFT_SIZE / 2 + 1
        // Frequency of each FFT bin (using FFT_SIZE-point transform)
        val freqBins = DoubleArray(nBins) { i -> i.toDouble() * SAMPLE_RATE / FFT_SIZE }

        return Array(N_MELS) { mel ->
            val lower  = melPoints[mel]
            val center = melPoints[mel + 1]
            val upper  = melPoints[mel + 2]
            FloatArray(nBins) { bin ->
                val freq = freqBins[bin]
                when {
                    freq <= lower || freq >= upper -> 0f
                    freq <= center -> ((freq - lower) / (center - lower)).toFloat()
                    else           -> ((upper - freq) / (upper - center)).toFloat()
                }
            }
        }
    }

    // ── Radix-2 Cooley-Tukey FFT (in-place, size must be power of 2) ──

    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size

        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                real[i] = real[j].also { real[j] = real[i] }
                imag[i] = imag[j].also { imag[j] = imag[i] }
            }
        }

        // Butterfly stages
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val angle = -2.0 * PI / len
            val wBaseReal = cos(angle).toFloat()
            val wBaseImag = sin(angle).toFloat()
            var pos = 0
            while (pos < n) {
                var uReal = 1f; var uImag = 0f
                for (k in 0 until halfLen) {
                    val lo = pos + k
                    val hi = lo + halfLen
                    val vReal = real[hi] * uReal - imag[hi] * uImag
                    val vImag = real[hi] * uImag + imag[hi] * uReal
                    real[hi] = real[lo] - vReal
                    imag[hi] = imag[lo] - vImag
                    real[lo] += vReal
                    imag[lo] += vImag
                    val newUReal = uReal * wBaseReal - uImag * wBaseImag
                    uImag = uReal * wBaseImag + uImag * wBaseReal
                    uReal = newUReal
                }
                pos += len
            }
            len = len shl 1
        }
    }
}
