package dev.dean.voice.model

import android.content.Context
import org.json.JSONObject

/**
 * Decodes Whisper output token IDs to UTF-8 text.
 *
 * Whisper uses GPT-2 byte-level BPE. Each token string encodes raw bytes
 * via a fixed 256-character Unicode mapping. To decode:
 *   1. Map token IDs → vocab strings (from assets/whisper_vocab.json).
 *   2. Concatenate strings, skipping special tokens (<|...|>).
 *   3. Apply inverse byte-to-unicode map → raw bytes → UTF-8 string.
 *
 * Requires assets/whisper_vocab.json — a JSON object mapping token strings
 * to their integer IDs, from the Hugging Face openai/whisper-small tokenizer.
 * Download with: python -c "from transformers import WhisperTokenizer; \
 *   import json; t=WhisperTokenizer.from_pretrained('openai/whisper-small'); \
 *   json.dump(t.get_vocab(), open('whisper_vocab.json','w'))"
 */
class WhisperTokenizer(context: Context) {

    companion object {
        // Whisper special token IDs (whisper-small multilingual)
        const val TOKEN_SOT            = 50258  // <|startoftranscript|>
        const val TOKEN_EN             = 50259  // <|en|>
        const val TOKEN_TRANSCRIBE     = 50359  // <|transcribe|>
        const val TOKEN_NO_TIMESTAMPS  = 50363  // <|notimestamps|>
        const val TOKEN_EOT            = 50257  // <|endoftext|>

        val PROMPT_TOKENS = intArrayOf(TOKEN_SOT, TOKEN_EN, TOKEN_TRANSCRIBE, TOKEN_NO_TIMESTAMPS)
    }

    private val idToVocab: Map<Int, String>

    // GPT-2 byte → unicode char mapping (inverse used for decoding)
    private val unicodeToBytes: Map<Char, Int> = buildBytesToUnicode()
        .entries.associate { (byte, char) -> char to byte }

    init {
        val json = context.assets.open("whisper_vocab.json")
            .bufferedReader()
            .use { it.readText() }
        val obj = JSONObject(json)
        val map = HashMap<Int, String>(obj.length())
        obj.keys().forEach { key -> map[obj.getInt(key)] = key }
        idToVocab = map
    }

    fun decode(tokenIds: List<Int>): String {
        val tokenStr = buildString {
            for (id in tokenIds) {
                val token = idToVocab[id] ?: continue
                if (token.startsWith("<|") && token.endsWith("|>")) continue
                append(token)
            }
        }
        // Reverse GPT-2 byte-level encoding: each char → original byte value
        val bytes = ByteArray(tokenStr.length) { i ->
            (unicodeToBytes[tokenStr[i]] ?: tokenStr[i].code).toByte()
        }
        return String(bytes, Charsets.UTF_8).trim()
    }

    // ── GPT-2 bytes_to_unicode ─────────────────────────────────────────
    // Maps byte values 0-255 to unique printable Unicode characters.
    // Printable ASCII and Latin-1 printable ranges map to themselves;
    // remaining 68 values map to consecutive code points starting at 256.

    private fun buildBytesToUnicode(): Map<Int, Char> {
        val printable = mutableListOf<Int>().apply {
            addAll('!'.code..'~'.code)    // 33–126
            addAll('¡'.code..'¬'.code)   // 161–172
            addAll('®'.code..'ÿ'.code)   // 174–255
        }
        val extra = (0..255).filter { it !in printable }
        val result = HashMap<Int, Char>(256)
        printable.forEach { b -> result[b] = b.toChar() }
        extra.forEachIndexed { i, b -> result[b] = (256 + i).toChar() }
        return result
    }
}
