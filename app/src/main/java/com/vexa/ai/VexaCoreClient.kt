package com.vexa.ai

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class VexaCoreClient(
    private val prefs: SharedPreferences,
    private val memory: MemoryStore
) {

    companion object {
        const val DEFAULT_GATEWAY_URL = "https://wolf-ai.amirmahdi-akhgari1390.workers.dev"
        const val FALLBACK_CLOUD_URL = "https://text.pollinations.ai/openai"

        fun isPersianText(text: String): Boolean = Regex("[\u0600-\u06FF]").containsMatchIn(text)

        private val BLOCKED_TERMS = listOf(
            "سکس", "پورن", "جنسی", "لخت", "برهنه", "عریان", "شهوانی", "اروتیک",
            "sex", "porn", "nude", "naked", "nsfw", "erotic", "xxx", "hentai"
        )

        fun containsBlockedContent(prompt: String): Boolean {
            val low = prompt.lowercase(Locale.ROOT)
            return BLOCKED_TERMS.any { low.contains(it) }
        }

        fun isLogoRequest(prompt: String): Boolean {
            val low = prompt.lowercase(Locale.ROOT)
            return Regex("(لوگو|آرم|نشان تجاری|برندینگ|logo|brand mark|emblem)").containsMatchIn(low)
        }
    }

    private val chatModels = listOf(
        "google/gemma-3-27b-it:free",
        "qwen/qwen3-235b-a22b:free",
        "meta-llama/llama-3.3-70b-instruct:free",
        "openrouter/free",
        "openai"
    )

    private val maxHistory = 36
    private val history = ArrayList<Pair<String, String>>()

    enum class Persona(val titleEn: String, val titleFa: String, val instruction: String) {
        ASSISTANT(
            "Vexa Global AI",
            "وِکسا هوشمند",
            "You are Vexa AI — a world-class, intelligent, multilingual assistant like ChatGPT. Understand user intent deeply, detect the user's exact language, and respond clearly and accurately in that exact language."
        ),
        TRADER(
            "Market Analyst",
            "تحلیلگر بازار",
            "You are Vexa AI in Market Analyst mode. Provide clear structure, key levels, risk management, and concise technical insight."
        ),
        CODER(
            "Software Engineer",
            "مهندس نرم‌افزار",
            "You are Vexa AI in Software Engineer mode. Write clean, production-grade code and clear explanations."
        ),
        TUTOR(
            "Language Tutor",
            "مترجم و مدرس زبان",
            "You are Vexa AI in Language Tutor mode. Translate and explain accurately while keeping natural phrasing."
        )
    }

    data class AiResult(
        val text: String,
        val generatedImage: Bitmap? = null,
        val generatedFile: FileGenerator.GeneratedFile? = null
    )

    fun getGatewayUrl(): String {
        val custom = prefs.getString("gateway_url", "").orEmpty().trim()
        return custom.ifEmpty { DEFAULT_GATEWAY_URL }
    }

    fun getPersona(): Persona {
        val name = prefs.getString("persona", Persona.ASSISTANT.name) ?: Persona.ASSISTANT.name
        return try { Persona.valueOf(name) } catch (_: Exception) { Persona.ASSISTANT }
    }

    fun setPersona(p: Persona) = prefs.edit().putString("persona", p.name).apply()

    @Synchronized
    fun clear() = history.clear()

    @Synchronized
    fun restoreHistory(stored: List<ChatHistoryStore.StoredMessage>) {
        history.clear()
        for (m in stored.takeLast(maxHistory)) {
            if (m.senderName == "USER") history.add("user" to m.text)
            else if (m.senderName == "VEXA") history.add("model" to m.text)
        }
    }

    /**
     * Deep ChatGPT-style prompt enhancer: understands Persian/any-language request and turns it
     * into a rich, vivid, detailed English visual prompt so the image generator renders exact intent.
     */
    private fun craftRichVisualPrompt(rawUserRequest: String, isLogo: Boolean): String {
        val sys = if (isLogo) {
            "You are an expert brand designer. Convert the user's logo idea into a concise, ultra-clean English prompt for an AI image generator (minimalist vector logo, clean geometry, centered, solid background, high contrast). Output ONLY the English prompt."
        } else {
            "You are an expert AI art director like DALL-E 3. Expand and translate the user's image request into a vivid, detailed, photorealistic English prompt describing subject, composition, lighting, colors, atmosphere, and camera style. Output ONLY the English prompt."
        }
        for (m in listOf("google/gemma-3-27b-it:free", "meta-llama/llama-3.3-70b-instruct:free", "openai")) {
            try {
                val res = callGateway(m, rawUserRequest, null, sys, includeHistory = false)
                    .replace("\"", "")
                    .replace("\n", " ")
                    .trim()
                if (res.length >= 4 && !isPersianText(res)) return res
            } catch (_: Exception) { }
        }
        return if (isLogo) {
            "minimalist modern vector logo icon, clean lines, professional graphic design, centered, solid background"
        } else {
            "$rawUserRequest, photorealistic, cinematic lighting, 8k resolution, sharp focus"
        }
    }

    @Synchronized
    fun generateImageOrLogo(rawPrompt: String, isFa: Boolean): AiResult {
        if (containsBlockedContent(rawPrompt)) {
            val msg = if (isFa) "امکان تولید تصویر با این موضوع وجود ندارد. سوژه مناسبی انتخاب کنید."
            else "Image generation for this topic is not allowed."
            return AiResult(msg)
        }
        val isLogo = isLogoRequest(rawPrompt)
        val richPromptEn = craftRichVisualPrompt(rawPrompt, isLogo)
        val seed = (System.currentTimeMillis() % 899999).toInt() + 1000
        val bmp = fetchRealBitmap(richPromptEn, seed)

        return if (bmp != null) {
            val caption = if (isLogo) {
                if (isFa) "✒️ لوگو ساخته شد:\n«$rawPrompt»" else "✒️ Logo created:\n\"$rawPrompt\""
            } else {
                if (isFa) "🎨 تصویر ساخته شد:\n«$rawPrompt»" else "🎨 Image created:\n\"$rawPrompt\""
            }
            AiResult(caption, generatedImage = bmp)
        } else {
            val errMsg = if (isFa) {
                "در حال حاضر موتور تصویرساز پاسخ نداد. لطفاً اتصال اینترنت را بررسی کنید و دوباره امتحان کنید."
            } else {
                "Image generator did not respond right now. Please check your connection and try again."
            }
            AiResult(errMsg)
        }
    }

    @Synchronized
    fun generateVideo(ctx: Context, rawPrompt: String, isFa: Boolean): AiResult {
        if (containsBlockedContent(rawPrompt)) {
            val msg = if (isFa) "امکان ساخت ویدیو با این موضوع وجود ندارد."
            else "Video generation for this topic is not allowed."
            return AiResult(msg)
        }
        val baseEn = craftRichVisualPrompt(rawPrompt, isLogo = false)
        val scenePrompts = listOf(
            "$baseEn, wide establishing cinematic shot, ultra detailed, 8k",
            "$baseEn, dynamic medium action shot, dramatic lighting, 8k",
            "$baseEn, dramatic close-up cinematic shot, depth of field, 8k"
        )
        val bitmaps = mutableListOf<Bitmap>()
        val baseSeed = (System.currentTimeMillis() % 899999).toInt() + 1000
        scenePrompts.forEachIndexed { idx, sp ->
            fetchRealBitmap(sp, baseSeed + idx * 31)?.let { bitmaps.add(it) }
        }
        if (bitmaps.isEmpty()) {
            return AiResult(
                if (isFa) "در حال حاضر دریافت فریم‌های واقعی ویدیو از سرور ناموفق بود. لطفاً دوباره امتحان کنید."
                else "Could not fetch real video frames from server right now. Please try again."
            )
        }
        while (bitmaps.size < 3) {
            bitmaps.add(bitmaps.last())
        }
        val vf = VideoGenerator.createCinematicMp4(ctx, bitmaps, rawPrompt, withSound = true)
        return if (vf != null) {
            val msg = if (isFa) {
                "🎬 ویدیوی سینمایی ${bitmaps.size * 2} ثانیه‌ای (MP4) ساخته شد:\n«$rawPrompt»\nبرای تماشا روی «پخش / باز کردن» بزنید."
            } else {
                "🎬 ${bitmaps.size * 2}s cinematic MP4 video ready:\n\"$rawPrompt\""
            }
            AiResult(msg, generatedImage = bitmaps.first(), generatedFile = vf)
        } else {
            AiResult(
                text = if (isFa) "🎬 فریم‌های ویدیو ساخته شد:\n«$rawPrompt»" else "🎬 Video frames ready:\n\"$rawPrompt\"",
                generatedImage = bitmaps.first()
            )
        }
    }

    private fun fetchRealBitmap(promptEn: String, seed: Int): Bitmap? {
        val enc = URLEncoder.encode(promptEn, "UTF-8")
        val base = getGatewayUrl().trimEnd('/')
        val endpoints = listOf(
            "$base/image?prompt=$enc&seed=$seed",
            "https://image.pollinations.ai/prompt/$enc?width=1024&height=1024&seed=$seed&nologo=true&model=flux",
            "https://image.pollinations.ai/prompt/$enc?width=1024&height=1024&seed=${seed + 7}&nologo=true&model=turbo"
        )
        for (u in endpoints) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 45000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                conn.setRequestProperty("Accept", "image/*,*/*")
                if (conn.responseCode == 200) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    if (bytes.size > 2500) {
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null && bmp.width >= 128 && bmp.height >= 128) return bmp
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { conn?.disconnect() } catch (_: Exception) { }
            }
        }
        return null
    }

    /**
     * Live web search via Cloudflare Worker (/search?q=...)
     */
    fun searchWebLive(query: String): String {
        val base = getGatewayUrl().trimEnd('/')
        val enc = URLEncoder.encode(query, "UTF-8")
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("$base/search?q=$enc").openConnection() as HttpURLConnection
            conn.connectTimeout = 12000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", "VexaAI-Android/16.0")
            if (conn.responseCode != 200) return ""
            val raw = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONObject(raw).optJSONArray("results") ?: return ""
            if (arr.length() == 0) return ""
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val title = item.optString("title", "").trim()
                val snippet = item.optString("snippet", "").trim()
                val link = item.optString("link", "").trim()
                if (title.isNotEmpty()) {
                    sb.append("${i + 1}. $title\n")
                    if (snippet.isNotEmpty()) sb.append("   $snippet\n")
                    if (link.isNotEmpty()) sb.append("   Source: $link\n")
                }
            }
            sb.toString().trim()
        } catch (_: Exception) {
            ""
        } finally {
            try { conn?.disconnect() } catch (_: Exception) { }
        }
    }

    private fun shouldAutoSearchWeb(text: String): Boolean {
        val low = text.lowercase(Locale.ROOT)
        return Regex(
            "(جستجو|جست‌وجو|سرچ|گوگل|خبر|اخبار|امروز|قیمت|نرخ|دلار|طلا|سکه|بیت کوین|بیت‌کوین|ارز|آب و هوا|هواشناسی|نتیجه بازی|جدیدترین|آخرین|search|google|news|latest|today|price|weather|current|2025|2026)"
        ).containsMatchIn(low)
    }

    @Synchronized
    fun generateRawFileContent(ext: String, topic: String): String? {
        val sys = "Output ONLY clean, complete raw content for a .$ext file about the request. No markdown fences (```)."
        for (m in chatModels) {
            try {
                val res = callGateway(m, "Format: .$ext\nTopic: $topic", null, sys, includeHistory = false)
                    .replace(Regex("^```[a-zA-Z]*\\n?"), "")
                    .replace(Regex("\\n?```$"), "")
                    .trim()
                if (res.isNotEmpty()) return res
            } catch (_: Exception) { }
        }
        return null
    }

    @Synchronized
    fun ask(
        text: String,
        image: Bitmap? = null,
        forceWebSearch: Boolean = false,
        forcePersona: Persona? = null,
        userDisplayName: String = ""
    ): AiResult {
        memory.autoLearnFromUserMessage(text)
        val isFa = isPersianText(text)
        val activePersona = forcePersona ?: getPersona()

        val doSearch = (image == null) && (forceWebSearch || shouldAutoSearchWeb(text))
        val webContext = if (doSearch) searchWebLive(text) else ""

        val sysPrompt = buildString {
            append(activePersona.instruction)
            if (userDisplayName.isNotBlank()) append("\nUser: $userDisplayName.")
            if (isFa) {
                append("\nReply clearly, thoroughly, and naturally in Persian.")
            } else {
                append("\nDetect the user's language and reply fluently in that exact same language.")
            }
            if (webContext.isNotEmpty()) {
                append("\n\n[LIVE WEB SEARCH RESULTS]:\n")
                append(webContext)
                append("\nUse these live web results to answer accurately and include relevant source links at the end.")
            }
            append(memory.asPromptContext())
        }

        var lastErr = ""
        for (m in chatModels) {
            try {
                val raw = callGateway(m, text, image, sysPrompt, includeHistory = true)
                    .replace(Regex("<think>[\\s\\S]*?</think>"), "")
                    .trim()
                if (raw.isNotEmpty()) {
                    val userLog = if (image != null) "[Image] $text" else text
                    history.add("user" to userLog)
                    history.add("model" to raw)
                    while (history.size > maxHistory) history.removeAt(0)
                    return AiResult(raw)
                }
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
            }
        }
        return AiResult(
            if (isFa) "ارتباط با سرور موقتاً قطع شد ($lastErr). اینترنت را بررسی کنید."
            else "Connection temporarily unavailable ($lastErr). Please check your network."
        )
    }

    private fun bitmapToDataUrl(bmp: Bitmap): String {
        val maxDim = 900
        val scale = minOf(maxDim.toFloat() / bmp.width, maxDim.toFloat() / bmp.height, 1f)
        val resized = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        } else bmp
        val baos = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 82, baos)
        return "data:image/jpeg;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun callGateway(
        model: String,
        text: String,
        image: Bitmap?,
        systemInstruction: String,
        includeHistory: Boolean
    ): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemInstruction))
        if (includeHistory) {
            for ((role, t) in history) {
                messages.put(JSONObject().put("role", if (role == "model") "assistant" else "user").put("content", t))
            }
        }
        if (image != null) {
            val arr = JSONArray()
            arr.put(JSONObject().put("type", "text").put("text", text.ifBlank { "Analyze this image." }))
            arr.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", bitmapToDataUrl(image))))
            messages.put(JSONObject().put("role", "user").put("content", arr))
        } else {
            messages.put(JSONObject().put("role", "user").put("content", text))
        }

        val endpoints = listOf(getGatewayUrl(), FALLBACK_CLOUD_URL).distinct()
        var lastEx: Exception = IOException("Connection failed")

        for (ep in endpoints) {
            val effModel = if (ep.contains("pollinations.ai")) "openai" else model
            val body = JSONObject().put("model", effModel).put("messages", messages)
            for (attempt in 1..2) {
                var conn: HttpURLConnection? = null
                try {
                    conn = URL(ep).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 15000
                    conn.readTimeout = 45000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.setRequestProperty("Connection", "close")
                    conn.setRequestProperty("User-Agent", "VexaAI-Android/16.0")
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                    if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
                    val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val choices = JSONObject(resp).optJSONArray("choices") ?: throw IOException("Empty choices")
                    if (choices.length() == 0) throw IOException("No choice")
                    val content = choices.getJSONObject(0).getJSONObject("message").optString("content", "")
                    if (content.isNotBlank()) return content
                } catch (e: Exception) {
                    lastEx = e
                    try { Thread.sleep(350L) } catch (_: InterruptedException) { }
                } finally {
                    try { conn?.disconnect() } catch (_: Exception) { }
                }
            }
        }
        throw lastEx
    }
}
