package com.invocta.domain.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.invocta.data.remote.api.SpeechApiClient
import com.invocta.data.remote.api.TtsOutputFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Server-Proxied TTS using Azure Speech Service
 * 
 * Features:
 * - Azure Neural voices for all supported languages
 * - MAI-Voice-1 voices for enhanced US English
 * - MP3 output for efficient playback
 */
class ServerTtsService(private val context: Context) {

    companion object {
        private const val TAG = "ServerTTS"
    }

    private val apiClient = SpeechApiClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var currentPlayJob: Job? = null
    private var tempAudioFile: File? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    /**
     * Initialize the service
     * Checks if server speech endpoints are available
     */
    suspend fun initialize(): Boolean {
        return try {
            val result = apiClient.getSpeechStatus()
            if (result.isSuccess) {
                val status = result.getOrNull()
                if (status?.enabled == true) {
                    Log.d(TAG, "Server TTS ready: model=${status.ttsModel}, voice=${status.defaultVoice}")
                    _isReady.value = true
                    return true
                }
            }
            Log.w(TAG, "Server TTS not available")
            _isReady.value = false
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize server TTS", e)
            _isReady.value = false
            false
        }
    }

    /**
     * Speak the given text using the server TTS API
     * Server automatically selects the best voice based on language detection.
     * 
     * @param text Text to convert to speech
     * @param utteranceId Identifier for this speech request
     */
    fun speak(
        text: String,
        utteranceId: String = "server_tts"
    ) {
        if (text.isBlank()) {
            Log.w(TAG, "Empty text, not speaking")
            return
        }

        // Cancel any ongoing speech
        stop()

        // Preprocess text for natural speech (remove markdown, convert symbols)
        val processedText = preprocessForSpeech(text)

        currentPlayJob = scope.launch {
            try {
                _isSpeaking.value = true
                Log.d(TAG, "Speaking: ${processedText.take(50)}...")

                // Get audio from server (server auto-selects voice)
                // Use 48kbps format for faster streaming with good quality
                val result = apiClient.textToSpeech(
                    text = processedText,
                    format = TtsOutputFormat.MP3_24K_48K
                )

                if (result.isFailure) {
                    Log.e(TAG, "TTS API failed: ${result.exceptionOrNull()?.message}")
                    _isSpeaking.value = false
                    return@launch
                }

                val audioData = result.getOrNull() ?: return@launch

                // Play audio
                playAudio(audioData, utteranceId)

            } catch (e: Exception) {
                Log.e(TAG, "Error during TTS", e)
                _isSpeaking.value = false
            }
        }
    }

    /**
     * Play audio data using MediaPlayer
     */
    private suspend fun playAudio(audioData: ByteArray, utteranceId: String) {
        withContext(Dispatchers.Main) {
            try {
                // Write to temp file (MediaPlayer needs file or URL)
                val tempFile = File.createTempFile("tts_$utteranceId", ".mp3", context.cacheDir)
                tempAudioFile = tempFile
                
                withContext(Dispatchers.IO) {
                    FileOutputStream(tempFile).use { it.write(audioData) }
                }

                // Create and configure MediaPlayer
                mediaPlayer?.release()
                mediaPlayer = android.media.MediaPlayer().apply {
                    setDataSource(tempFile.absolutePath)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setOnCompletionListener {
                        Log.d(TAG, "Playback completed: $utteranceId")
                        _isSpeaking.value = false
                        cleanup()
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                        _isSpeaking.value = false
                        cleanup()
                        true
                    }
                    prepare()
                    start()
                }

                Log.d(TAG, "Playback started: $utteranceId")

            } catch (e: Exception) {
                Log.e(TAG, "Error playing audio", e)
                _isSpeaking.value = false
                cleanup()
            }
        }
    }

    /**
     * Stop any ongoing speech
     */
    fun stop() {
        currentPlayJob?.cancel()
        currentPlayJob = null
        
        mediaPlayer?.let {
            try {
                if (it.isPlaying) {
                    it.stop()
                }
            } catch (e: Exception) {
                // Ignore
            }
            it.release()
        }
        mediaPlayer = null
        
        _isSpeaking.value = false
        cleanup()
    }

    /**
     * Clean up temporary files
     */
    private fun cleanup() {
        tempAudioFile?.let {
            try {
                it.delete()
            } catch (e: Exception) {
                // Ignore
            }
        }
        tempAudioFile = null
    }

    /**
     * Check if currently speaking
     */
    fun isSpeakingNow(): Boolean = mediaPlayer?.isPlaying == true

    /**
     * Release all resources
     */
    fun shutdown() {
        stop()
        scope.cancel()
        apiClient.close()
        _isReady.value = false
        Log.d(TAG, "Server TTS shutdown")
    }

    /**
     * Process text to sound more natural when spoken.
     * - Removes markdown formatting but preserves punctuation for pauses
     * - Converts symbols to spoken words
     * - Converts lists to comma-separated items for faster reading
     */
    private fun preprocessForSpeech(text: String): String {
        var processed = text

        // Step 1: Remove code blocks first (they don't make sense spoken)
        processed = processed.replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " code block omitted ")
        processed = processed.replace(Regex("`[^`]+`")) { it.value.trim('`') }

        // Step 2: Remove markdown bold/italic markers but keep the text
        // Handle ***bold italic*** first
        processed = processed.replace(Regex("\\*\\*\\*(.+?)\\*\\*\\*")) { it.groupValues[1] }
        // Handle **bold**
        processed = processed.replace(Regex("\\*\\*(.+?)\\*\\*")) { it.groupValues[1] }
        // Handle __bold__
        processed = processed.replace(Regex("__(.+?)__")) { it.groupValues[1] }
        // Handle *italic* - but not bullet points (those start a line)
        processed = processed.replace(Regex("(?<=[\\s(])\\*([^*\\n]+)\\*(?=[\\s.,;:!?)])")) { it.groupValues[1] }
        // Handle _italic_
        processed = processed.replace(Regex("(?<=[\\s(])_([^_\\n]+)_(?=[\\s.,;:!?)])")) { it.groupValues[1] }

        // Step 3: Remove markdown headers (# ## ### etc) but keep the text
        processed = processed.replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")

        // Step 4: Remove markdown links [text](url), keep the text
        processed = processed.replace(Regex("\\[([^\\]]+)]\\([^)]+\\)")) { it.groupValues[1] }

        // Step 5: Convert markdown lists to comma-separated items for faster reading
        processed = convertListsToCommaSeparated(processed)

        // Step 6: Handle blockquotes - remove > but keep text
        processed = processed.replace(Regex("^>+\\s*", RegexOption.MULTILINE), "")

        // Step 7: Handle horizontal rules
        processed = processed.replace(Regex("^[-*_]{3,}\\s*$", RegexOption.MULTILINE), " ")

        // Step 8: Convert common symbols to spoken words
        processed = processed.replace(" & ", " and ")
        processed = processed.replace("&", " and ")
        processed = processed.replace(" / ", " or ")  // slash between words means "or"
        processed = processed.replace("/", " or ")    // slash without spaces
        processed = processed.replace("%", " percent")
        processed = processed.replace("->", " to ")
        processed = processed.replace("=>", " results in ")
        processed = processed.replace("...", ", ")

        // Step 9: Convert dashes used as separators to pauses
        processed = processed.replace(" - ", ", ")
        processed = processed.replace(" – ", ", ")
        processed = processed.replace(" — ", ", ")

        // Step 10: Remove emojis
        processed = processed.replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+"), "")
        processed = processed.replace(Regex("[\\u2600-\\u27BF]"), "")
        processed = processed.replace(Regex("[\\u2300-\\u23FF]"), "")
        processed = processed.replace(Regex("[\\u2B50-\\u2B55]"), "")

        // Step 11: Clean up newlines - convert to pauses
        processed = processed.replace(Regex("\\n{2,}"), ". ")
        processed = processed.replace(Regex("\\n"), " ")

        // Step 12: Clean up multiple spaces
        processed = processed.replace(Regex("\\s+"), " ")

        // Step 13: Clean up punctuation issues
        processed = processed.replace(Regex("\\.\\s*\\.+"), ".")
        processed = processed.replace(Regex(",\\s*,+"), ",")
        processed = processed.replace(Regex("^[\\s.,;:]+"), "")

        // Ensure text ends cleanly
        processed = processed.trim()
        if (processed.isNotEmpty() && !processed.endsWith(".") && !processed.endsWith("!") && !processed.endsWith("?")) {
            processed += "."
        }

        Log.d(TAG, "Processed for TTS: ${processed.take(100)}...")

        return processed
    }

    /**
     * Convert markdown lists (bullet points and numbered lists) to comma-separated items.
     * This makes TTS read lists faster and more naturally.
     */
    private fun convertListsToCommaSeparated(text: String): String {
        val lines = text.split("\n")
        val result = StringBuilder()
        var listItems = mutableListOf<String>()

        for (line in lines) {
            val trimmedLine = line.trim()

            // Check if line is a list item (bullet or numbered)
            val isBulletItem = trimmedLine.matches(Regex("^[-*•]\\s+.+"))
            val isNumberedItem = trimmedLine.matches(Regex("^\\d+[.):]\\s+.+"))

            if (isBulletItem || isNumberedItem) {
                // Extract the item text (remove bullet/number)
                val itemText = if (isBulletItem) {
                    trimmedLine.replaceFirst(Regex("^[-*•]\\s+"), "").trim()
                } else {
                    trimmedLine.replaceFirst(Regex("^\\d+[.):]\\s+"), "").trim()
                }

                // Remove trailing punctuation for cleaner joining
                val cleanedItem = itemText.trimEnd('.', ',', ';')

                if (cleanedItem.isNotEmpty()) {
                    listItems.add(cleanedItem)
                }
            } else {
                // Not a list item - flush any accumulated list items
                if (listItems.isNotEmpty()) {
                    result.append(formatListAsCommaSeparated(listItems))
                    listItems = mutableListOf()
                }

                // Add the non-list line
                if (trimmedLine.isNotEmpty()) {
                    if (result.isNotEmpty() && !result.endsWith(" ") && !result.endsWith("\n")) {
                        result.append(" ")
                    }
                    result.append(trimmedLine)
                }
            }
        }

        // Flush remaining list items at end
        if (listItems.isNotEmpty()) {
            result.append(formatListAsCommaSeparated(listItems))
        }

        return result.toString()
    }

    /**
     * Format a list of items as a comma-separated string with "and" before the last item.
     */
    private fun formatListAsCommaSeparated(items: List<String>): String {
        return when {
            items.isEmpty() -> ""
            items.size == 1 -> " ${items[0]}."
            items.size == 2 -> " ${items[0]} and ${items[1]}."
            else -> {
                val allButLast = items.dropLast(1).joinToString(", ")
                val last = items.last()
                " $allButLast, and $last."
            }
        }
    }
}
