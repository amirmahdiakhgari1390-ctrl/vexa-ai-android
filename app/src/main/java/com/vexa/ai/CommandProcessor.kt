package com.vexa.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class CommandProcessor(
    private val ctx: Context,
    private val phone: PhoneActions,
    private val vexaCore: VexaCoreClient,
    private val memory: MemoryStore,
    private val accounts: AccountManager
) {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    enum class ToolMode { AUTO, IMAGE, VIDEO, SEARCH }

    data class ProcessResponse(
        val text: String,
        val generatedImage: Bitmap? = null,
        val generatedFile: FileGenerator.GeneratedFile? = null
    )

    fun norm(s: String): String {
        val sb = StringBuilder()
        for (c in s.lowercase()) {
            sb.append(
                when (c) {
                    'ي' -> 'ی'
                    'ك' -> 'ک'
                    '\u200c' -> ' '
                    in '۰'..'۹' -> '0' + (c - '۰')
                    in '٠'..'٩' -> '0' + (c - '٠')
                    else -> c
                }
            )
        }
        return sb.toString().replace(Regex("[؟?!،؛,]"), " ").replace(Regex("\\s+"), " ").trim().trim('.', ' ')
    }

    private fun has(cmd: String, vararg keys: String) = keys.any { cmd.contains(norm(it)) }
    private fun hasW(cmd: String, vararg keys: String): Boolean {
        val t = cmd.split(" ")
        return keys.any { norm(it) in t }
    }

    fun process(
        raw: String,
        image: Bitmap? = null,
        toolMode: ToolMode = ToolMode.AUTO,
        onReply: (ProcessResponse) -> Unit
    ) {
        val isFa = VexaCoreClient.isPersianText(raw)
        val cmd = norm(raw)

        if (image != null) {
            io.execute {
                val res = vexaCore.ask(raw, image = image, userDisplayName = accounts.getCurrentUser()?.fullName.orEmpty())
                main.post { onReply(ProcessResponse(res.text)) }
            }
            return
        }

        if (cmd.isBlank()) {
            onReply(ProcessResponse(""))
            return
        }

        val vidRegex = Regex("(تولید ویدیو|ساخت ویدیو|ویدیو بساز|فیلم بساز|کلیپ بساز|ویدئو بساز|یه فیلم|یک فیلم|یه ویدیو|یک ویدیو|generate video|create video|make a video|video of)")
        if (toolMode == ToolMode.VIDEO || vidRegex.containsMatchIn(cmd)) {
            val topic = raw.replace(
                Regex("(please|a|an|the|لطفاً|لطفا|یک|یه|تولید ویدیو از|تولید ویدیو|ساخت ویدیو از|ساخت ویدیو|ویدیو بساز از|ویدیو بساز|فیلم بساز از|فیلم بساز|کلیپ بساز از|کلیپ بساز|generate video of|create video of|make a video of)", RegexOption.IGNORE_CASE),
                " "
            ).replace(Regex("\\s+"), " ").trim().ifEmpty { raw }

            io.execute {
                val aiRes = vexaCore.generateVideo(ctx, topic, isFa)
                main.post {
                    onReply(ProcessResponse(aiRes.text, aiRes.generatedImage, aiRes.generatedFile))
                }
            }
            return
        }

        val imgRegex = Regex("(تولید عکس|ساخت عکس|عکس بساز|عکس درست کن|تصویر بساز|نقاشی بکش|طراحی کن|لوگو بساز|طراحی لوگو|آرم بساز|یه عکس|یک عکس|یه تصویر|یک تصویر|generate image|create image|draw a|create logo|design a logo|picture of)")
        if (toolMode == ToolMode.IMAGE || imgRegex.containsMatchIn(cmd)) {
            val desc = raw.replace(
                Regex("(please|a|an|the|لطفاً|لطفا|یک|یه|تولید عکس از|ساخت عکس از|عکس بساز از|عکس بساز|تصویر بساز از|نقاشی بکش از|طراحی لوگو برای|لوگو بساز برای|لوگو بساز|generate image of|create image of|create logo for)", RegexOption.IGNORE_CASE),
                " "
            ).replace(Regex("\\s+"), " ").trim().ifEmpty { raw }

            io.execute {
                val aiRes = vexaCore.generateImageOrLogo(desc, isFa)
                main.post {
                    onReply(ProcessResponse(aiRes.text, aiRes.generatedImage))
                }
            }
            return
        }

        val wantsFile = (has(cmd, "فایل") && has(cmd, "بساز", "تولید کن", "درست کن", "ایجاد کن")) ||
            (has(cmd, "file") && has(cmd, "create", "generate", "make"))
        if (wantsFile) {
            val ext = detectExt(cmd)
            if (ext != null) {
                io.execute {
                    val content = vexaCore.generateRawFileContent(ext, raw)
                    if (content.isNullOrBlank()) {
                        main.post { onReply(ProcessResponse(if (isFa) "تولید فایل ناموفق بود." else "File generation failed.")) }
                    } else {
                        val gf = FileGenerator.createFile(ctx, ext, "Vexa_${ext.uppercase()}", content)
                        val msg = if (isFa) "فایل ${gf.displayName} ساخته شد." else "File ${gf.displayName} created."
                        main.post { onReply(ProcessResponse(msg, generatedFile = gf)) }
                    }
                }
                return
            }
        }

        if (toolMode != ToolMode.SEARCH) {
            val local = handleLocal(cmd, isFa)
            if (local != null) {
                onReply(ProcessResponse(local))
                return
            }
        }

        io.execute {
            val res = vexaCore.ask(
                raw,
                image = null,
                forceWebSearch = (toolMode == ToolMode.SEARCH),
                userDisplayName = accounts.getCurrentUser()?.fullName.orEmpty()
            )
            main.post { onReply(ProcessResponse(res.text)) }
        }
    }

    private fun detectExt(cmd: String): String? = when {
        has(cmd, "pdf", "پی دی اف", "پی‌دی‌اف") -> "pdf"
        has(cmd, "python", "پایتون", "py") -> "py"
        has(cmd, "csv", "اکسل", "جدول") -> "csv"
        has(cmd, "html", "سایت", "صفحه وب") -> "html"
        has(cmd, "kotlin", "کاتلین", "kt") -> "kt"
        has(cmd, "json", "جیسون") -> "json"
        has(cmd, "md", "مارک داون") -> "md"
        has(cmd, "txt", "متنی", "متن") -> "txt"
        else -> null
    }

    private fun handleLocal(cmd: String, isFa: Boolean): String? {
        if (has(cmd, "حافظه بلندمدت را پاک کن", "حافظه رو پاک کن", "clear memory")) {
            vexaCore.clear()
            return memory.clearAll(isFa)
        }
        if (has(cmd, "چی یادته", "چی به خاطر داری", "what do you remember")) {
            val facts = memory.getAll()
            return if (facts.isEmpty()) {
                if (isFa) "چیزی در حافظه ثبت نشده است." else "Nothing in memory yet."
            } else {
                if (isFa) "موارد ثبت‌شده: ${facts.joinToString(" ، ")}" else "Learned: ${facts.joinToString(", ")}"
            }
        }
        val remKey = listOf("به خاطر بسپار که", "به خاطر بسپار", "یادت باشه", "یاد بگیر", "remember that", "remember")
            .firstOrNull { has(cmd, it) }
        if (remKey != null) {
            return memory.remember(cmd.substringAfter(norm(remKey)).trim(), isFa)
        }
        if (has(cmd, "گفتگو را فراموش کن", "چت را پاک کن", "clear chat")) {
            vexaCore.clear()
            return if (isFa) "گفتگوی فعلی پاک شد." else "Chat cleared."
        }
        if (has(cmd, "واتساپ", "whatsapp") && has(cmd, "پیام", "بفرست", "send", "message")) {
            val after = if (cmd.contains(" به ")) cmd.substringAfter(" به ") else cmd.substringAfter(" to ")
            val parts = after.split(Regex("(پیام بده|بگو|بفرست|که|saying|:)"), limit = 2)
            if (parts.size == 2 && parts[0].trim().isNotEmpty()) {
                return phone.sendWhatsApp(parts[0].trim(), parts[1].trim(), isFa)
            }
        }
        val smsKey = listOf("پیامک بده به", "اس ام اس بده به", "send sms to").firstOrNull { has(cmd, it) }
        if (smsKey != null) {
            val rest = cmd.substringAfter(norm(smsKey)).trim()
            val tokens = rest.split(" ")
            if (tokens.isNotEmpty()) {
                return phone.sendSms(tokens[0], tokens.drop(1).joinToString(" ").trim(), isFa)
            }
        }
        val navKey = listOf("مسیریابی به", "مسیر به", "navigate to").firstOrNull { has(cmd, it) }
        if (navKey != null) {
            val dest = cmd.substringAfter(norm(navKey)).trim()
            if (dest.isNotEmpty()) return phone.navigateTo(dest, isFa)
        }
        if (has(cmd, "یوتیوب", "youtube") && has(cmd, "پخش", "بگرد", "جستجو", "play", "search")) {
            val q = cmd.replace(Regex("(در یوتیوب|یوتیوب|پخش کن|جستجو کن|on youtube|youtube|play|search)"), " ").trim()
            if (q.isNotEmpty()) return phone.playYouTube(q, isFa)
        }
        if (has(cmd, "کلیپ بورد", "کلیپ‌بورد", "clipboard")) return phone.readClipboard(isFa)
        if (has(cmd, "شیر یا خط", "سکه بنداز", "flip a coin")) return phone.flipCoin(isFa)
        if (has(cmd, "تاس بنداز", "roll a dice")) return phone.rollDice(isFa)
        if (has(cmd, "رمز عبور بساز", "generate password")) return phone.randomPassword(12, isFa)
        if (has(cmd, "وای فای", "وایفای", "wifi")) return phone.openWifiPanel(isFa)
        if (has(cmd, "بلوتوث", "bluetooth")) return phone.openBluetoothSettings(isFa)
        if (has(cmd, "نور صفحه", "روشنایی", "brightness")) return phone.openDisplaySettings(isFa)
        if (has(cmd, "حالت ویبره", "گوشی را سایلنت", "vibrate mode")) return phone.setRingerMode(true, isFa)
        if (has(cmd, "حالت عادی", "از سایلنت دربیار", "normal mode")) return phone.setRingerMode(false, isFa)
        if (has(cmd, "چراغ قوه", "فلش", "flashlight", "torch")) {
            val off = has(cmd, "خاموش") || hasW(cmd, "off")
            val on = has(cmd, "روشن", "بزن") || hasW(cmd, "on")
            return phone.torch(if (off) false else if (on) true else null, isFa)
        }
        if (has(cmd, "صدا", "ولوم", "volume")) {
            if (has(cmd, "حداکثر", "max")) return phone.volume(2, isFa)
            if (has(cmd, "بی صدا", "قطع", "mute")) return phone.volume(0, isFa)
            if (has(cmd, "زیاد", "بالا", "up")) return phone.volume(1, isFa)
            if (has(cmd, "کم", "پایین", "down")) return phone.volume(-1, isFa)
        }
        if (has(cmd, "باتری", "شارژ", "battery")) return phone.battery(isFa)
        val callKey = listOf("تماس بگیر با", "تماس بگیر", "زنگ بزن به", "زنگ بزن", "call").firstOrNull { has(cmd, it) }
        if (callKey != null) {
            val k = norm(callKey)
            val target = (cmd.substringAfter(k).ifBlank { cmd.substringBefore(k) })
                .replace(Regex("(^| )(با|به|شماره|لطفا|please)( |$)"), " ").trim()
            if (target.isEmpty()) return if (isFa) "با چه کسی تماس بگیرم؟" else "Who should I call?"
            return phone.call(target, isFa)
        }
        if (has(cmd, "آلارم", "alarm")) {
            val nums = Regex("\\d+").findAll(cmd).map { it.value.toInt() }.toList()
            var h = nums.getOrNull(0) ?: Numbers.parse(cmd)?.toInt()
                ?: return if (isFa) "ساعت آلارم را بگویید." else "What time?"
            var m = nums.getOrNull(1) ?: 0
            if (m == 0 && has(cmd, "و نیم")) m = 30
            if (h < 12 && has(cmd, "عصر", "شب", "pm")) h += 12
            if (h !in 0..23 || m !in 0..59) return if (isFa) "ساعت نامعتبر است." else "Invalid time."
            return phone.alarm(h, m, isFa)
        }
        if (has(cmd, "تایمر", "timer")) {
            val n = Numbers.parse(cmd) ?: return if (isFa) "زمان تایمر را بگویید." else "How long?"
            val secs = when {
                has(cmd, "ثانیه", "second") -> n
                has(cmd, "ساعت", "hour") -> n * 3600
                else -> n * 60
            }
            return phone.timer(secs.toInt(), isFa)
        }
        if (has(cmd, "یادداشت", "note")) {
            if (has(cmd, "بخون", "بخوان", "read")) return phone.readNotes(isFa)
            val content = cmd.replace(Regex("(یادداشت کن|یادداشت|take a note|note)"), "").trim()
            if (content.isEmpty()) return if (isFa) "متن یادداشت را بگویید." else "What should I note?"
            return phone.saveNote(content, isFa)
        }
        if (has(cmd, "تاریخ", "امروز چندم", "date")) return Jalali.todayText(isFa)
        if (has(cmd, "ساعت چنده", "ساعت چند", "what time") || cmd == "ساعت" || cmd == "time") {
            val c = Calendar.getInstance()
            val h = c.get(Calendar.HOUR_OF_DAY)
            val m = c.get(Calendar.MINUTE)
            return if (isFa) {
                if (m == 0) "ساعت $h است." else "ساعت $h و $m دقیقه است."
            } else {
                "Time: ${SimpleDateFormat("hh:mm a", Locale.US).format(Date())}"
            }
        }
        var expr = cmd
        listOf("به علاوه" to "+", "منهای" to "-", "ضرب در" to "*", "تقسیم بر" to "/", "plus" to "+", "minus" to "-", "times" to "*", "divided by" to "/")
            .forEach { (k, v) -> expr = expr.replace(k, " $v ") }
        expr = expr.replace(Regex("(حساب کن|محاسبه کن|چند میشه|calculate|what is|=)"), " ").trim()
        if (Regex("[+\\-*/]").containsMatchIn(expr) && expr.matches(Regex("[\\d\\s+\\-*/().]+"))) {
            val v = Calc.eval(expr)
            if (v != null && !v.isNaN() && !v.isInfinite()) {
                val fmt = Calc.format(v)
                return if (isFa) "جواب $fmt است." else "Result: $fmt"
            }
        }
        val openKey = listOf("باز کن", "اجرا کن", "open", "launch").firstOrNull { has(cmd, it) }
        if (openKey != null) {
            val name = cmd.replace(norm(openKey), " ").replace(Regex("(^| )(برنامه|اپ|را|رو|لطفا|app|the|please)( |$)"), " ").trim()
            if (name.isEmpty()) return if (isFa) "کدام برنامه باز شود؟" else "Which app?"
            return phone.openApp(name, isFa)
        }
        return null
    }
}
