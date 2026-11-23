package com.vexa.ai

object AppStringsAndThemes {

    enum class AppTheme(
        val id: String,
        val titleFa: String,
        val titleEn: String,
        val bgRoot: String,
        val bgHeader: String,
        val bgCard: String,
        val bgInput: String,
        val border: String,
        val accent: String,
        val textPrimary: String,
        val textSecondary: String
    ) {
        CHATGPT_DARK("gpt_dark", "مشکی مدرن (ChatGPT)", "Modern Dark", "#09090B", "#121215", "#18181B", "#27272A", "#3F3F46", "#10A37F", "#FAFAFA", "#A1A1AA"),
        OBSIDIAN_CYAN("obsidian", "آبی نئونی", "Obsidian Cyan", "#050811", "#0A101E", "#0F172A", "#111827", "#1E293B", "#22D3EE", "#F8FAFC", "#94A3B8"),
        MIDNIGHT_GOLD("gold", "مشکی و طلایی", "Midnight Gold", "#090806", "#14110C", "#1C1812", "#241F17", "#383024", "#F59E0B", "#FEFCE8", "#D6C7B2"),
        ROYAL_VIOLET("violet", "بنفش کهکشانی", "Royal Violet", "#090512", "#120A24", "#1A1033", "#221642", "#352463", "#C084FC", "#FAF5FF", "#D8B4FE"),
        PURE_LIGHT("light", "روشن", "Pure Light", "#F8FAFC", "#FFFFFF", "#FFFFFF", "#F1F5F9", "#CBD5E1", "#10A37F", "#0F172A", "#475569")
    }

    data class WorldLang(val code: String, val shortLabel: String, val displayName: String, val isRtl: Boolean)

    val SUPPORTED_UI_LANGUAGES = listOf(
        WorldLang("fa-IR", "FA", "فارسی (Persian)", true),
        WorldLang("en-US", "EN", "English (انگلیسی)", false),
        WorldLang("ar-SA", "AR", "العربية (عربی)", true),
        WorldLang("tr-TR", "TR", "Türkçe (ترکی)", false),
        WorldLang("fr-FR", "FR", "Français (فرانسوی)", false),
        WorldLang("de-DE", "DE", "Deutsch (آلمانی)", false),
        WorldLang("es-ES", "ES", "Español (اسپانیایی)", false),
        WorldLang("ru-RU", "RU", "Русский (روسی)", false),
        WorldLang("zh-CN", "ZH", "中文 (چینی)", false)
    )

    fun tr(langCode: String, key: String): String {
        val lang = langCode.substringBefore("-").lowercase()
        return if (lang in listOf("fa", "ar")) faMap[key] ?: enMap[key] ?: key else enMap[key] ?: key
    }

    private val faMap = mapOf(
        "thinking" to "در حال فکر کردن و جست‌وجو…",
        "input_hint" to "از Vexa AI بپرسید… (یا + را برای ساخت عکس، فیلم و سرچ وب بزنید)",
        "new_chat" to "+ گفتگوی جدید",
        "history_title" to "تاریخچه گفتگوها",
        "verified_user" to "تایید شده ✓",
        "logout" to "خروج",
        "tool_image" to "🎨 ساخت عکس حرفه‌ای",
        "tool_video" to "🎬 ساخت ویدیو سینمایی MP4",
        "tool_search" to "🌐 جست‌وجوی زنده در گوگل",
        "tool_gallery" to "🖼️ انتخاب عکس از گالری",
        "tool_camera" to "📷 گرفتن عکس با دوربین",
        "tool_pdf" to "📄 ساخت فایل PDF",
        "mode_normal" to "حالت عادی",
        "mode_image" to "🎨 ساخت عکس فعال است",
        "mode_video" to "🎬 ساخت ویدیو فعال است",
        "mode_search" to "🌐 جست‌وجوی وب فعال است",
        "settings_title" to "تنظیمات Vexa AI",
        "save" to "ذخیره",
        "close" to "بستن",
        "welcome_title" to "چطور می‌توانم کمکتان کنم؟",
        "welcome_sub" to "دکمه + کنار کادر پیام را بزنید یا مستقیم دستور ساخت عکس، ویدیو، جست‌وجوی گوگل یا کدنویسی بدهید."
    )

    private val enMap = mapOf(
        "thinking" to "Thinking & searching…",
        "input_hint" to "Message Vexa AI… (Tap + for Image, Video & Web Search)",
        "new_chat" to "+ New Chat",
        "history_title" to "Chat History",
        "verified_user" to "Verified ✓",
        "logout" to "Logout",
        "tool_image" to "🎨 Create AI Image",
        "tool_video" to "🎬 Create Cinematic Video MP4",
        "tool_search" to "🌐 Live Google Web Search",
        "tool_gallery" to "🖼️ Upload from Gallery",
        "tool_camera" to "📷 Take Photo with Camera",
        "tool_pdf" to "📄 Create PDF Document",
        "mode_normal" to "Standard Mode",
        "mode_image" to "🎨 Image Generation Active",
        "mode_video" to "🎬 Video Generation Active",
        "mode_search" to "🌐 Live Web Search Active",
        "settings_title" to "Vexa AI Settings",
        "save" to "Save",
        "close" to "Close",
        "welcome_title" to "What can I help with?",
        "welcome_sub" to "Tap + next to the input bar or ask directly for images, cinematic videos, live Google search, or coding."
    )
}
