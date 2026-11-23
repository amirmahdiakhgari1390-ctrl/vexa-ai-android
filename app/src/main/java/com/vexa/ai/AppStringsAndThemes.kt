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
        OBSIDIAN_CYAN("obsidian", "مشکی نئونی", "Obsidian Cyan", "#050811", "#0A101E", "#0F172A", "#111827", "#1E293B", "#22D3EE", "#F8FAFC", "#94A3B8"),
        MIDNIGHT_GOLD("gold", "مشکی و طلایی", "Midnight Gold", "#090806", "#14110C", "#1C1812", "#241F17", "#383024", "#F59E0B", "#FEFCE8", "#D6C7B2"),
        EMERALD_MATRIX("emerald", "سبز زمردی", "Emerald Dark", "#040D0A", "#081A14", "#0D261E", "#123328", "#1B4738", "#10B981", "#ECFDF5", "#A7F3D0"),
        ROYAL_VIOLET("violet", "بنفش کهکشانی", "Royal Violet", "#090512", "#120A24", "#1A1033", "#221642", "#352463", "#C084FC", "#FAF5FF", "#D8B4FE"),
        PURE_LIGHT("light", "روشن", "Pure Light", "#F1F5F9", "#FFFFFF", "#FFFFFF", "#E2E8F0", "#CBD5E1", "#0284C7", "#0F172A", "#475569")
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
        "tap_to_speak" to "برای صحبت کردن گوی را لمس کنید",
        "listening" to "در حال گوش دادن…",
        "thinking" to "در حال پردازش…",
        "speaking" to "در حال صحبت…",
        "input_hint" to "پیام به هر زبانی… (عکس، فیلم، کد، جست‌وجو)",
        "wake_label" to "بیدارباش صوتی: فقط با گفتن «وِکسا» پاسخ دهد",
        "new_chat" to "+ گفتگوی جدید",
        "menu_custom_train" to "شخصی‌سازی و آموزش نامحدود",
        "menu_theme_lang" to "تم و زبان برنامه",
        "menu_server" to "تنظیمات سرور",
        "history_title" to "تاریخچه گفتگوها",
        "verified_user" to "تایید شده ✓",
        "logout" to "خروج",
        "chip_video" to "🎬 ساخت فیلم MP4",
        "chip_video_cmd" to "فیلم بساز از یک فضانورد در غروب مریخ",
        "chip_photo" to "📸 ساخت عکس",
        "chip_photo_cmd" to "عکس بساز از یک کلبه چوبی کنار دریاچه در مه صبحگاهی",
        "chip_train" to "🧠 آموزش نامحدود",
        "chip_theme" to "🎨 تغییر تم و زبان",
        "chip_btc" to "📈 تحلیل بیت‌کوین",
        "chip_btc_cmd" to "تحلیل قیمت و روند بیت کوین را بگو",
        "chip_pdf" to "📄 ساخت PDF",
        "chip_pdf_cmd" to "یک فایل pdf بساز درباره مدیریت سرمایه",
        "chip_chart" to "📊 تحلیل تصویر",
        "settings_title" to "تنظیمات Vexa AI",
        "save" to "ذخیره",
        "close" to "بستن",
        "welcome_body" to "سلام %s عزیز! به Vexa AI خوش آمدی:\n• 🌍 گفتگوی چندزبانه\n• 📸 ساخت عکس و 🎬 تولید فیلم MP4\n• 🧠 آموزش نامحدود و تغییر تم از تنظیمات"
    )

    private val enMap = mapOf(
        "tap_to_speak" to "Tap the orb to speak",
        "listening" to "Listening…",
        "thinking" to "Thinking…",
        "speaking" to "Speaking…",
        "input_hint" to "Message in any language… (Image, Video, Code)",
        "wake_label" to "Wake word: Respond only when saying \"Vexa\"",
        "new_chat" to "+ New Chat",
        "menu_custom_train" to "Unlimited Training",
        "menu_theme_lang" to "Theme & Language",
        "menu_server" to "Server Settings",
        "history_title" to "Chat History",
        "verified_user" to "Verified ✓",
        "logout" to "Logout",
        "chip_video" to "🎬 Create MP4 Video",
        "chip_video_cmd" to "Create a video of an astronaut on Mars at sunset",
        "chip_photo" to "📸 Create Image",
        "chip_photo_cmd" to "Create an image of a wooden cabin by a misty lake at sunrise",
        "chip_train" to "🧠 Unlimited Training",
        "chip_theme" to "🎨 Theme & Language",
        "chip_btc" to "📈 Bitcoin Analysis",
        "chip_btc_cmd" to "Analyze Bitcoin price and market trend",
        "chip_pdf" to "📄 Create PDF",
        "chip_pdf_cmd" to "Create a PDF file about risk management",
        "chip_chart" to "📊 Analyze Image",
        "settings_title" to "Vexa AI Settings",
        "save" to "Save",
        "close" to "Close",
        "welcome_body" to "Welcome %s to Vexa AI!\n• 🌍 Multilingual chat\n• 📸 Image & 🎬 MP4 video creation\n• 🧠 Unlimited training & themes in Settings"
    )
}
