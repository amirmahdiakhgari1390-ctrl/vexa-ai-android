package com.vexa.ai

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
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

    private val maxHistory = 32
    private val history = ArrayList<Pair<String, String>>()

    enum class Persona(val titleEn: String, val titleFa: String, val instruction: String) {
        ASSISTANT(
            "Vexa Global AI",
            "وِکسا هوشمند",
            "You are Vexa AI — a clear, helpful, multilingual assistant. Detect the user's language and reply naturally in that exact language."
        ),
        TRADER(
            "Market Analyst",
            "تحلیلگر بازار",
            "You are Vexa AI in Market Analyst mode. Provide clear structure, key levels, risk management, and concise technical insight."
        ),
        CODER(
            "Software Engineer",
            "مهندس نرم‌افزار",
            "You are Vexa AI in Software Engineer mode. Write clean, accurate, well-structured code and clear explanations."
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
        val generatedFile: FileGenerator.GeneratedFile? = null,
        val isCodeOrTechnical: Boolean = false
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

    @Synchronized
    fun generateImageOrLogo(rawPrompt: String, isFa: Boolean): AiResult {
        if (containsBlockedContent(rawPrompt)) {
            val msg = if (isFa) "امکان تولید تصویر با این موضوع وجود ندارد. سوژه مناسبی انتخاب کنید."
            else "Image generation for this topic is not allowed."
            return AiResult(msg)
        }
        val isLogo = isLogoRequest(rawPrompt)
        val subjectEn = translateToEnglish(rawPrompt, isLogo).ifBlank { rawPrompt }
        val finalPrompt = if (isLogo) {
            "minimalist vector logo design for $subjectEn, clean modern geometric icon, solid background, high contrast"
        } else {
            "$subjectEn, high quality photography, realistic lighting, crisp detail"
        }
        val seed = (System.currentTimeMillis() % 899999).toInt() + 1000
        val bmp = fetchBitmapFromGateway(finalPrompt, seed) ?: renderStudioCanvas(rawPrompt, isLogo, 0)
        val caption = if (isLogo) {
            if (isFa) "✒️ لوگوی آماده شد:\n«$rawPrompt»" else "✒️ Logo ready:\n\"$rawPrompt\""
        } else {
            if (isFa) "📸 تصویر آماده شد:\n«$rawPrompt»" else "📸 Image ready:\n\"$rawPrompt\""
        }
        return AiResult(caption, generatedImage = bmp)
    }

    @Synchronized
    fun generateVideo(ctx: Context, rawPrompt: String, isFa: Boolean): AiResult {
        if (containsBlockedContent(rawPrompt)) {
            val msg = if (isFa) "امکان ساخت ویدیو با این موضوع وجود ندارد."
            else "Video generation for this topic is not allowed."
            return AiResult(msg)
        }
        val subjectEn = translateToEnglish(rawPrompt, isLogo = false).ifBlank { rawPrompt }
        val scenes = listOf(
            "$subjectEn, wide cinematic shot, realistic lighting",
            "$subjectEn, medium shot, smooth motion, realistic lighting",
            "$subjectEn, close-up detail shot, realistic lighting"
        )
        val bitmaps = mutableListOf<Bitmap>()
        val baseSeed = (System.currentTimeMillis() % 899999).toInt() + 1000
        scenes.forEachIndexed { idx, sp ->
            fetchBitmapFromGateway(sp, baseSeed + idx * 19)?.let { bitmaps.add(it) }
        }
        if (bitmaps.isEmpty()) {
            for (i in 0..2) bitmaps.add(renderStudioCanvas(rawPrompt, isLogo = false, sceneIdx = i))
        }
        val vf = VideoGenerator.createCinematicMp4(ctx, bitmaps, rawPrompt, withSound = true)
        return if (vf != null) {
            val msg = if (isFa) {
                "🎬 ویدیوی ${bitmaps.size * 2} ثانیه‌ای آماده شد:\n«$rawPrompt»\nبرای پخش روی «باز کردن» بزنید."
            } else {
                "🎬 ${bitmaps.size * 2}s video ready:\n\"$rawPrompt\""
            }
            AiResult(msg, generatedImage = bitmaps.first(), generatedFile = vf)
        } else {
            AiResult(
                text = if (isFa) "🎬 صحنه ویدیو آماده شد:\n«$rawPrompt»" else "🎬 Video scene ready:\n\"$rawPrompt\"",
                generatedImage = bitmaps.first()
            )
        }
    }

    private fun renderStudioCanvas(title: String, isLogo: Boolean, sceneIdx: Int): Bitmap {
        val w = 720
        val h = 720
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val gradColors = if (isLogo) {
            intArrayOf(Color.parseColor("#070B16"), Color.parseColor("#0F172A"), Color.parseColor("#0891B2"))
        } else {
            when (sceneIdx % 3) {
                0 -> intArrayOf(Color.parseColor("#090D16"), Color.parseColor("#0E7490"), Color.parseColor("#2563EB"))
                1 -> intArrayOf(Color.parseColor("#180C2E"), Color.parseColor("#7E22CE"), Color.parseColor("#DB2777"))
                else -> intArrayOf(Color.parseColor("#052E2B"), Color.parseColor("#0D9488"), Color.parseColor("#0284C7"))
            }
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), gradColors, null, Shader.TileMode.CLAMP)
        })
        canvas.drawCircle(360f, 320f, 130f + sceneIdx * 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(65, 255, 255, 255)
        })
        canvas.drawText(if (isLogo) "VEXA LOGO" else "VEXA STUDIO", 360f, 135f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#22D3EE")
            textSize = 22f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        })
        canvas.drawText(title.take(36), 360f, 360f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        })
        return bmp
    }

    private fun translateToEnglish(raw: String, isLogo: Boolean): String {
        val clean = raw.trim()
        if (!isPersianText(clean)) return clean
        val sys = if (isLogo) {
            "Translate this Persian logo prompt into a concise English logo description. Output ONLY English."
        } else {
            "Translate this Persian visual prompt into a clear English description. Output ONLY English."
        }
        for (m in listOf("google/gemma-3-27b-it:free", "openai")) {
            try {
                val res = callGateway(m, clean, null, sys, includeHistory = false)
                    .replace("\"", "").replace("\n", " ").trim()
                if (res.length >= 2 && !isPersianText(res)) return res
            } catch (_: Exception) { }
        }
        return ""
    }

    private fun fetchBitmapFromGateway(promptEn: String, seed: Int): Bitmap? {
        val enc = URLEncoder.encode(promptEn, "UTF-8")
        val base = getGatewayUrl().trimEnd('/')
        val endpoints = listOf(
            "$base/image?prompt=$enc&seed=$seed",
            "https://image.pollinations.ai/prompt/$enc?width=768&height=768&seed=$seed&nologo=true&model=flux",
            "https://image.pollinations.ai/prompt/$enc?width=768&height=768&seed=${seed + 5}&nologo=true&model=turbo"
        )
        for (u in endpoints) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 12000
                conn.readTimeout = 35000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                conn.setRequestProperty("Accept", "image/*,*/*")
                if (conn.responseCode == 200) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null && bmp.width >= 128 && bmp.height >= 128) return bmp
                }
            } catch (_: Exception) {
            } finally {
                try { conn?.disconnect() } catch (_: Exception) { }
            }
        }
        return null
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
        forcePersona: Persona? = null,
        userDisplayName: String = ""
    ): AiResult {
        memory.autoLearnFromUserMessage(text)
        val isFa = isPersianText(text)
        val activePersona = forcePersona ?: getPersona()
        val isCode = activePersona == Persona.CODER ||
            Regex("(کد|برنامه|اسکریپت|پایتون|کاتلین|code|program|script|python|kotlin|html|css|js|sql|bug)", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)

        val sysPrompt = buildString {
            append(activePersona.instruction)
            if (userDisplayName.isNotBlank()) append("\nUser: $userDisplayName.")
            if (isFa) {
                append("\nReply clearly and naturally in Persian.")
            } else {
                append("\nDetect the user's language and reply fluently in that exact same language.")
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
                    return AiResult(raw, isCodeOrTechnical = isCode)
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
                    conn.setRequestProperty("User-Agent", "VexaAI-Android/15.0")
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
