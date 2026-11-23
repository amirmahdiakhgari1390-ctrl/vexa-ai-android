package com.vexa.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

class VexaVoiceEngine(
    private val ctx: Context,
    private val onStateChange: (isSpeaking: Boolean) -> Unit
) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    @Volatile
    private var cancelled = false

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.setSpeechRate(1.02f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        handler.post { onStateChange(true) }
                    }

                    override fun onDone(utteranceId: String?) {
                        handler.post { onStateChange(false) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        handler.post { onStateChange(false) }
                    }
                })
            }
        }
    }

    fun detectLanguageCode(text: String, preferredLangTag: String = "fa-IR"): String {
        if (Regex("[پچژگکی]").containsMatchIn(text)) return "fa"
        if (Regex("[\\u0600-\\u06FF]").containsMatchIn(text)) {
            return if (preferredLangTag.startsWith("ar")) "ar" else "fa"
        }
        if (Regex("[\\u0400-\\u04FF]").containsMatchIn(text)) return "ru"
        if (Regex("[\\u4E00-\\u9FFF]").containsMatchIn(text)) return "zh-CN"
        if (Regex("[\\u3040-\\u30FF]").containsMatchIn(text)) return "ja"
        if (Regex("[\\uAC00-\\uD7AF]").containsMatchIn(text)) return "ko"
        if (Regex("[\\u0900-\\u097F]").containsMatchIn(text)) return "hi"
        if (Regex("[ğĞıİşŞ]").containsMatchIn(text)) return "tr"
        if (Regex("[ßäöüÄÖÜ]").containsMatchIn(text)) return "de"
        if (Regex("[éèêëàâçîïôûùÉÈÊÀÇ]").containsMatchIn(text)) return "fr"
        if (Regex("[ñÑ¿¡áíóúÁÍÓÚ]").containsMatchIn(text)) return "es"
        val pref = preferredLangTag.substringBefore("-").lowercase(Locale.ROOT)
        if (pref in listOf("fr", "de", "es", "tr", "it", "pt", "nl")) return pref
        return "en"
    }

    fun cleanForSpeech(raw: String, langCode: String): String {
        val codeNotice = if (langCode == "fa") " کد برنامه روی صفحه نمایش داده شد. " else " Code block is on screen. "
        return raw
            .replace(Regex("```[\\s\\S]*?```"), codeNotice)
            .replace(Regex("[*#`_~•🎬📸🧠📱💬✨🌐🎨]"), " ")
            .replace("Vexa AI", if (langCode == "fa") "وِکسا" else "Vexa AI")
            .replace(Regex("https?://\\S+"), if (langCode == "fa") " لینک " else " link ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun speak(rawText: String, preferredLangTag: String = "fa-IR") {
        val langCode = detectLanguageCode(rawText, preferredLangTag)
        val text = cleanForSpeech(rawText, langCode)
        if (text.isBlank()) {
            onStateChange(false)
            return
        }
        cancelled = false
        stopMediaPlayerOnly()

        if (ttsReady && tts != null) {
            val loc = Locale.forLanguageTag(
                when (langCode) {
                    "fa" -> "fa-IR"; "ar" -> "ar-SA"; "tr" -> "tr-TR"; "fr" -> "fr-FR"
                    "de" -> "de-DE"; "es" -> "es-ES"; "ru" -> "ru-RU"; "zh-CN" -> "zh-CN"
                    "ja" -> "ja-JP"; "ko" -> "ko-KR"; "hi" -> "hi-IN"; else -> "en-US"
                }
            )
            val res = tts?.setLanguage(loc)
            if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                onStateChange(true)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vexa_tts")
                return
            }
        }
        speakViaCloudTts(text, langCode)
    }

    private fun speakViaCloudTts(fullText: String, langCode: String) {
        onStateChange(true)
        val chunks = splitChunks(fullText, 180)
        io.execute {
            try {
                for (chunk in chunks) {
                    if (cancelled) break
                    val f = fetchTtsAudio(chunk, langCode)
                    if (f != null && !cancelled) playBlocking(f)
                }
            } catch (_: Exception) {
            } finally {
                handler.post { onStateChange(false) }
            }
        }
    }

    private fun splitChunks(text: String, maxLen: Int): List<String> {
        val parts = text.split(Regex("(?<=[.!؟،؛。！？\\n])\\s+"))
        val out = mutableListOf<String>()
        var sb = StringBuilder()
        for (p in parts) {
            if (p.isBlank()) continue
            if (sb.length + p.length + 1 <= maxLen) {
                if (sb.isNotEmpty()) sb.append(" ")
                sb.append(p)
            } else {
                if (sb.isNotEmpty()) {
                    out.add(sb.toString())
                    sb = StringBuilder()
                }
                if (p.length <= maxLen) sb.append(p) else out.add(p.take(maxLen))
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private fun fetchTtsAudio(snippet: String, langCode: String): File? {
        val enc = URLEncoder.encode(snippet, "UTF-8")
        val base = VexaCoreClient.DEFAULT_GATEWAY_URL.trimEnd('/')
        val urls = listOf(
            "$base/tts?lang=$langCode&text=$enc",
            "https://translate.googleapis.com/translate_tts?ie=UTF-8&tl=$langCode&client=gtx&q=$enc"
        )
        for (u in urls) {
            try {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 12000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (conn.responseCode == 200) {
                    val tmp = File(ctx.cacheDir, "vexa_tts_${System.nanoTime()}.mp3")
                    conn.inputStream.use { inp -> FileOutputStream(tmp).use { out -> inp.copyTo(out) } }
                    conn.disconnect()
                    if (tmp.exists() && tmp.length() > 300L) return tmp
                } else conn.disconnect()
            } catch (_: Exception) { }
        }
        return null
    }

    private fun playBlocking(file: File) {
        val lock = Object()
        var done = false
        handler.post {
            if (cancelled) {
                synchronized(lock) { done = true; lock.notifyAll() }
                return@post
            }
            try {
                stopMediaPlayerOnly()
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .build()
                    )
                    setDataSource(file.absolutePath)
                    setOnCompletionListener {
                        file.delete()
                        synchronized(lock) { done = true; lock.notifyAll() }
                    }
                    setOnErrorListener { _, _, _ ->
                        file.delete()
                        synchronized(lock) { done = true; lock.notifyAll() }
                        true
                    }
                    prepare()
                    start()
                }
            } catch (_: Exception) {
                file.delete()
                synchronized(lock) { done = true; lock.notifyAll() }
            }
        }
        synchronized(lock) {
            while (!done && !cancelled) {
                try { lock.wait(12000) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun stopMediaPlayerOnly() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) { }
        mediaPlayer = null
    }

    fun stop() {
        cancelled = true
        tts?.stop()
        stopMediaPlayerOnly()
        onStateChange(false)
    }

    fun shutdown() {
        cancelled = true
        tts?.shutdown()
        stopMediaPlayerOnly()
        io.shutdownNow()
    }
}
