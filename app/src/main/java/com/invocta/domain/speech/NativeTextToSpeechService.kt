package com.invocta.domain.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Native Android text-to-speech service.
 * Used as fallback when Azure Speech Service is not configured.
 */
class NativeTextToSpeechService(private val context: Context) {

    companion object {
        private const val TAG = "NativeTTS"
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    /**
     * Initialize the TTS engine
     */
    fun initialize() {
        if (tts != null) return

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Language not supported")
                    _isReady.value = false
                } else {
                    isInitialized = true
                    _isReady.value = true
                    Log.d(TAG, "TTS initialized successfully")
                    
                    // Set speech rate slightly faster for more natural conversation
                    tts?.setSpeechRate(1.0f)
                    tts?.setPitch(1.0f)
                    
                    // Set up progress listener
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _isSpeaking.value = true
                            Log.d(TAG, "Started speaking: $utteranceId")
                        }

                        override fun onDone(utteranceId: String?) {
                            _isSpeaking.value = false
                            Log.d(TAG, "Finished speaking: $utteranceId")
                        }

                        @Deprecated("Deprecated in API level 21")
                        override fun onError(utteranceId: String?) {
                            _isSpeaking.value = false
                            Log.e(TAG, "Error speaking: $utteranceId")
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            _isSpeaking.value = false
                            Log.e(TAG, "Error speaking: $utteranceId, code: $errorCode")
                        }
                    })
                }
            } else {
                Log.e(TAG, "TTS initialization failed with status: $status")
                _isReady.value = false
            }
        }
    }

    /**
     * Speak the given text aloud.
     * Text is processed to sound more natural for TTS.
     */
    fun speak(text: String, utteranceId: String = "agent_response") {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized, initializing now...")
            initialize()
            return
        }

        val processedText = preprocessForSpeech(text)
        
        tts?.speak(processedText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        Log.d(TAG, "Speaking: ${processedText.take(50)}...")
    }

    /**
     * Queue text to speak after current speech completes
     */
    fun queueSpeak(text: String, utteranceId: String = "agent_response_queued") {
        if (!isInitialized) return

        val processedText = preprocessForSpeech(text)
        tts?.speak(processedText, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    /**
     * Stop speaking immediately
     */
    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    /**
     * Release TTS resources
     */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        _isReady.value = false
        _isSpeaking.value = false
    }

    /**
     * Process text to sound more natural when spoken.
     * - Removes markdown formatting but preserves punctuation for pauses
     * - Converts symbols to spoken words
     * - Handles bullet points and lists naturally
     */
    private fun preprocessForSpeech(text: String): String {
        var processed = text
        
        Log.d(TAG, "Original text: ${text.take(200)}...")

        // Step 1: Remove code blocks first (they don't make sense spoken)
        processed = processed.replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " code block omitted ")
        processed = processed.replace(Regex("`[^`]+`"), { it.value.trim('`') })

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

        // Step 10: Remove emojis using character class
        // This covers most common emoji ranges
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

        Log.d(TAG, "Processed text: ${processed.take(200)}...")
        
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

    /**
     * Check if TTS is currently speaking
     */
    fun isSpeakingNow(): Boolean = tts?.isSpeaking == true
}

