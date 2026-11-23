package com.vexa.ai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var accounts: AccountManager
    private lateinit var memoryStore: MemoryStore
    private lateinit var historyStore: ChatHistoryStore
    private lateinit var currentSession: ChatHistoryStore.ChatSession
    private lateinit var vexaCore: VexaCoreClient
    private lateinit var processor: CommandProcessor

    private var uiLangCode = "fa-IR"
    private var currentTheme = AppStringsAndThemes.AppTheme.CHATGPT_DARK
    private var pendingImage: Bitmap? = null
    private var selectedToolMode = CommandProcessor.ToolMode.AUTO

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var drawerSessionsContainer: LinearLayout
    private lateinit var statusBanner: TextView
    private lateinit var personaChip: TextView
    private lateinit var iqToggleBtn: TextView
    private lateinit var langToggleBtn: TextView
    private lateinit var emptyHeroBox: LinearLayout
    private lateinit var chatRecycler: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var commandInput: EditText
    private lateinit var activeToolBadgeRow: LinearLayout
    private lateinit var activeToolLabel: TextView
    private lateinit var plusToolsDrawer: LinearLayout
    private lateinit var imagePreviewBar: LinearLayout
    private lateinit var imagePreviewThumb: ImageView

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val bmp = res.data?.extras?.get("data") as? Bitmap
            if (bmp != null) attachImage(bmp)
        }
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val uri = res.data?.data ?: return@registerForActivityResult
            try {
                contentResolver.openInputStream(uri)?.use { inp ->
                    BitmapFactory.decodeStream(inp)?.let { attachImage(it) }
                }
            } catch (_: Exception) {
                Toast.makeText(this, "خطا در بارگذاری تصویر", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("vexa_prefs", Context.MODE_PRIVATE)
        uiLangCode = prefs.getString("ui_lang_code", "fa-IR") ?: "fa-IR"
        val savedTheme = prefs.getString("app_theme_id", AppStringsAndThemes.AppTheme.CHATGPT_DARK.id)
        currentTheme = AppStringsAndThemes.AppTheme.values().firstOrNull { it.id == savedTheme }
            ?: AppStringsAndThemes.AppTheme.CHATGPT_DARK

        accounts = AccountManager(this)
        memoryStore = MemoryStore(this)
        historyStore = ChatHistoryStore(this)
        vexaCore = VexaCoreClient(prefs, memoryStore)
        processor = CommandProcessor(this, PhoneActions(this), vexaCore, memoryStore, accounts)

        if (!accounts.isLoggedIn()) showAuthScreen() else launchMainAssistant()
    }

    private fun t(key: String): String = AppStringsAndThemes.tr(uiLangCode, key)
    private fun currentLangObj(): AppStringsAndThemes.WorldLang =
        AppStringsAndThemes.SUPPORTED_UI_LANGUAGES.firstOrNull { it.code == uiLangCode }
            ?: AppStringsAndThemes.SUPPORTED_UI_LANGUAGES.first()

    private fun launchMainAssistant() {
        buildChatGptStyleUi()
        loadInitialSession()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun roundedBg(fillHex: String, radiusDp: Int, strokeHex: String? = null, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeHex != null) setStroke(dp(strokeDp), Color.parseColor(strokeHex))
        }

    private fun showAuthScreen() {
        var registerMode = true
        var awaitingOtp = false

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(currentTheme.bgRoot))
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(38), dp(24), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_vexa_logo)
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply { bottomMargin = dp(10) }
        }
        val title = TextView(this).apply {
            text = "VEXA AI"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(currentTheme.textPrimary))
        }
        val subtitle = TextView(this).apply {
            text = "دستیار هوش مصنوعی جهانی"
            textSize = 13f
            setTextColor(Color.parseColor(currentTheme.accent))
            setPadding(0, dp(4), 0, dp(22))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(currentTheme.bgHeader, 18, currentTheme.border, 1)
            setPadding(dp(18), dp(20), dp(18), dp(20))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val modeTitle = TextView(this).apply {
            text = "ثبت‌نام و تایید شماره تلفن"
            textSize = 16.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(currentTheme.textPrimary))
            setPadding(0, 0, 0, dp(14))
        }
        val nameInput = EditText(this).apply {
            hint = "نام و نام خانوادگی"
            setHintTextColor(Color.parseColor("#71717A"))
            setTextColor(Color.parseColor(currentTheme.textPrimary))
            textSize = 14f
            background = roundedBg(currentTheme.bgInput, 12, currentTheme.border, 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        }
        val phoneInput = EditText(this).apply {
            hint = "09123456789"
            inputType = InputType.TYPE_CLASS_PHONE
            setHintTextColor(Color.parseColor("#71717A"))
            setTextColor(Color.parseColor(currentTheme.textPrimary))
            textSize = 14f
            background = roundedBg(currentTheme.bgInput, 12, currentTheme.border, 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        }
        val passRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
        }
        val passInput = EditText(this).apply {
            hint = "رمز عبور (حداقل ۶ کاراکتر)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setHintTextColor(Color.parseColor("#71717A"))
            setTextColor(Color.parseColor(currentTheme.textPrimary))
            textSize = 14f
            background = roundedBg(currentTheme.bgInput, 12, currentTheme.border, 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
        }
        val autoPassBtn = Button(this).apply {
            text = "🔐 رمز امن"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#09090B"))
            background = roundedBg(currentTheme.accent, 12)
            setOnClickListener {
                val p = accounts.generateStrongPassword()
                passInput.setText(p)
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Vexa Password", p))
                Toast.makeText(this@MainActivity, "رمز ساخته و کپی شد: $p", Toast.LENGTH_LONG).show()
            }
        }
        passRow.addView(passInput)
        passRow.addView(autoPassBtn)

        val otpBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = roundedBg(currentTheme.bgCard, 12, currentTheme.accent, 1)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
        }
        val otpNotice = TextView(this).apply {
            text = "کد تایید ۵ رقمی VEXA AI:"
            textSize = 12f
            setTextColor(Color.parseColor(currentTheme.accent))
            setPadding(0, 0, 0, dp(8))
        }
        val otpInput = EditText(this).apply {
            hint = "کد ۵ رقمی"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(5))
            gravity = Gravity.CENTER
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#34D399"))
            background = roundedBg(currentTheme.bgInput, 10, "#10A37F", 1)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        otpBox.addView(otpNotice)
        otpBox.addView(otpInput)

        val submitBtn = Button(this).apply {
            text = "📩 ارسال کد تایید ۵ رقمی"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#09090B"))
            background = roundedBg(currentTheme.accent, 14)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        }
        val switchBtn = TextView(this).apply {
            text = "حساب دارید؟ ورود"
            textSize = 12.5f
            setTextColor(Color.parseColor(currentTheme.accent))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }
        switchBtn.setOnClickListener {
            registerMode = !registerMode
            awaitingOtp = false
            otpBox.visibility = View.GONE
            if (registerMode) {
                modeTitle.text = "ثبت‌نام و تایید شماره تلفن"
                nameInput.visibility = View.VISIBLE
                autoPassBtn.visibility = View.VISIBLE
                submitBtn.text = "📩 ارسال کد تایید ۵ رقمی"
                switchBtn.text = "حساب دارید؟ ورود"
            } else {
                modeTitle.text = "ورود به حساب"
                nameInput.visibility = View.GONE
                autoPassBtn.visibility = View.GONE
                submitBtn.text = "🔓 ورود"
                switchBtn.text = "حساب ندارید؟ ثبت‌نام"
            }
        }
        submitBtn.setOnClickListener {
            if (!registerMode) {
                val res = accounts.login(phoneInput.text.toString(), passInput.text.toString())
                Toast.makeText(this, res.second, Toast.LENGTH_LONG).show()
                if (res.first) launchMainAssistant()
                return@setOnClickListener
            }
            if (!awaitingOtp) {
                val (ok, msg, _) = accounts.sendVerificationOtp(nameInput.text.toString(), phoneInput.text.toString(), passInput.text.toString())
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (ok) {
                    awaitingOtp = true
                    otpNotice.text = msg
                    otpBox.visibility = View.VISIBLE
                    submitBtn.text = "✅ تایید کد و ثبت‌نام"
                }
            } else {
                val (ok, msg) = accounts.verifyOtpAndCompleteRegistration(otpInput.text.toString())
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (ok) {
                    accounts.getCurrentUser()?.let { memoryStore.setUserNickname(it.fullName) }
                    launchMainAssistant()
                }
            }
        }

        card.addView(modeTitle)
        card.addView(nameInput)
        card.addView(phoneInput)
        card.addView(passRow)
        card.addView(otpBox)
        card.addView(submitBtn)
        card.addView(switchBtn)
        root.addView(logo)
        root.addView(title)
        root.addView(subtitle)
        root.addView(card)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun buildChatGptStyleUi() {
        val langObj = currentLangObj()
        val dir = if (langObj.isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

        drawerLayout = DrawerLayout(this).apply {
            setBackgroundColor(Color.parseColor(currentTheme.bgRoot))
            layoutDirection = dir
        }

        val mainContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(currentTheme.bgRoot))
            layoutDirection = dir
            layoutParams = DrawerLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Top Minimal Header (ChatGPT-style)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.parseColor(currentTheme.bgRoot))
        }

        val menuBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_menu)
            background = null
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                refreshDrawerHistoryList()
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        val centerModelBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showPersonaDialog() }
        }

        val logoIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_vexa_logo)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(6) }
        }

        personaChip = TextView(this).apply {
            text = "Vexa AI ▾"
            textSize = 16.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(currentTheme.textPrimary))
        }
        centerModelBox.addView(logoIcon)
        centerModelBox.addView(personaChip)

        iqToggleBtn = TextView(this).apply {
            val isDeep = vexaCore.getIntelligenceLevel() == VexaCoreClient.IntelligenceLevel.DEEP_THINK
            text = if (isDeep) "🧠 Deep" else "⚡ Fast"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(currentTheme.accent))
            background = roundedBg(currentTheme.bgCard, 10, currentTheme.border, 1)
            setPadding(dp(8), dp(5), dp(8), dp(5))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                val next = if (vexaCore.getIntelligenceLevel() == VexaCoreClient.IntelligenceLevel.DEEP_THINK) {
                    VexaCoreClient.IntelligenceLevel.FAST
                } else {
                    VexaCoreClient.IntelligenceLevel.DEEP_THINK
                }
                vexaCore.setIntelligenceLevel(next)
                text = if (next == VexaCoreClient.IntelligenceLevel.DEEP_THINK) "🧠 Deep" else "⚡ Fast"
                Toast.makeText(
                    this@MainActivity,
                    if (next == VexaCoreClient.IntelligenceLevel.DEEP_THINK) "حالت هوش عمیق فعال شد" else "حالت پاسخ سریع فعال شد",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        langToggleBtn = TextView(this).apply {
            text = langObj.shortLabel
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(currentTheme.textSecondary))
            background = roundedBg(currentTheme.bgCard, 10, currentTheme.border, 1)
            setPadding(dp(8), dp(5), dp(8), dp(5))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
            setOnClickListener { showLanguagePickerDialog() }
        }

        val newChatTopBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_new_chat)
            background = null
            setPadding(dp(7), dp(7), dp(7), dp(7))
            setOnClickListener { startNewConversation() }
        }

        val settingsBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            background = null
            setPadding(dp(7), dp(7), dp(7), dp(7))
            setOnClickListener { showSettingsDialog() }
        }

        topBar.addView(menuBtn)
        topBar.addView(centerModelBox)
        topBar.addView(iqToggleBtn)
        topBar.addView(langToggleBtn)
        topBar.addView(newChatTopBtn)
        topBar.addView(settingsBtn)

        // Thinking/Status Banner (appears smoothly when processing)
        statusBanner = TextView(this).apply {
            text = t("thinking")
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(currentTheme.accent))
            background = roundedBg(currentTheme.bgCard, 16, currentTheme.border, 1)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
        }

        // Center Stage (Hero Screen when chat is empty + RecyclerView)
        val centerStage = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        emptyHeroBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(20), dp(26), dp(20))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val heroLogo = ImageView(this).apply {
            setImageResource(R.drawable.ic_vexa_logo)
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { bottomMargin = dp(14) }
        }
        val heroTitle = TextView(this).apply {
            text = t("welcome_title")
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(currentTheme.textPrimary))
            setPadding(0, 0, 0, dp(8))
        }
        val heroSub = TextView(this).apply {
            text = t("welcome_sub")
            textSize = 13f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.35f)
            setTextColor(Color.parseColor(currentTheme.textSecondary))
            setPadding(0, 0, 0, dp(20))
        }

        // Quick Action Cards on Empty Screen
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val quickItems = listOf(
            "🎨 ساخت عکس" to CommandProcessor.ToolMode.IMAGE,
            "🎬 ساخت فیلم" to CommandProcessor.ToolMode.VIDEO,
            "🌐 سرچ گوگل" to CommandProcessor.ToolMode.SEARCH
        )
        for ((qLbl, qMode) in quickItems) {
            val qBtn = TextView(this).apply {
                text = qLbl
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(currentTheme.textPrimary))
                background = roundedBg(currentTheme.bgCard, 18, currentTheme.border, 1)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(6)
                    marginStart = dp(6)
                }
                setOnClickListener {
                    selectToolMode(qMode)
                    commandInput.requestFocus()
                }
            }
            quickRow.addView(qBtn)
        }

        emptyHeroBox.addView(heroLogo)
        emptyHeroBox.addView(heroTitle)
        emptyHeroBox.addView(heroSub)
        emptyHeroBox.addView(quickRow)

        chatAdapter = ChatAdapter(mutableListOf())
        chatRecycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply { stackFromEnd = true }
            adapter = chatAdapter
            setPadding(dp(14), dp(8), dp(14), dp(8))
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        centerStage.addView(emptyHeroBox)
        centerStage.addView(chatRecycler)

        // Bottom ChatGPT-style Composer Area
        val bottomComposer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(currentTheme.bgRoot))
            setPadding(dp(12), dp(6), dp(12), dp(12))
        }

        // Expandable "+" Tools Menu (Image, Video, Google Search, Gallery, Camera, PDF)
        plusToolsDrawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = roundedBg(currentTheme.bgHeader, 18, currentTheme.border, 1)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }

        val toolGrid = listOf(
            t("tool_image") to { selectToolMode(CommandProcessor.ToolMode.IMAGE); plusToolsDrawer.visibility = View.GONE },
            t("tool_video") to { selectToolMode(CommandProcessor.ToolMode.VIDEO); plusToolsDrawer.visibility = View.GONE },
            t("tool_search") to { selectToolMode(CommandProcessor.ToolMode.SEARCH); plusToolsDrawer.visibility = View.GONE },
            t("tool_gallery") to { plusToolsDrawer.visibility = View.GONE; openGallery() },
            t("tool_camera") to { plusToolsDrawer.visibility = View.GONE; openCamera() },
            t("tool_pdf") to {
                plusToolsDrawer.visibility = View.GONE
                commandInput.setText("یک فایل pdf بساز درباره ")
                commandInput.setSelection(commandInput.text.length)
                commandInput.requestFocus()
            }
        )
        for ((label, action) in toolGrid) {
            val rowItem = TextView(this).apply {
                text = label
                textSize = 13.5f
                setTextColor(Color.parseColor(currentTheme.textPrimary))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener { action() }
            }
            plusToolsDrawer.addView(rowItem)
        }

        // Active Tool Badge (e.g., "🎨 ساخت عکس فعال است  ✕")
        activeToolBadgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = roundedBg(currentTheme.bgCard, 14, currentTheme.accent, 1)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        activeToolLabel = TextView(this).apply {
            text = ""
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(currentTheme.accent))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(10)
            }
        }
        val clearToolBtn = TextView(this).apply {
            text = "✕"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(currentTheme.textSecondary))
            setOnClickListener { selectToolMode(CommandProcessor.ToolMode.AUTO) }
        }
        activeToolBadgeRow.addView(activeToolLabel)
        activeToolBadgeRow.addView(clearToolBtn)

        // Attached Image Preview Bar
        imagePreviewBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(currentTheme.bgCard, 14, currentTheme.accent, 1)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        imagePreviewThumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) }
        }
        val imgLbl = TextView(this).apply {
            text = "تصویر پیوست شد"
            textSize = 12f
            setTextColor(Color.parseColor(currentTheme.accent))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val rmImg = TextView(this).apply {
            text = "✕"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F87171"))
            setOnClickListener { clearAttachedImage() }
        }
        imagePreviewBar.addView(imagePreviewThumb)
        imagePreviewBar.addView(imgLbl)
        imagePreviewBar.addView(rmImg)

        // Pill Search Box (ChatGPT-style with "+" button on side and Up-Arrow Send button)
        val pillInputBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(currentTheme.bgCard, 26, currentTheme.border, 1)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        val plusBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_plus)
            background = roundedBg(currentTheme.bgInput, 18)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                plusToolsDrawer.visibility = if (plusToolsDrawer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }

        commandInput = EditText(this).apply {
            hint = t("input_hint")
            setHintTextColor(Color.parseColor(currentTheme.textSecondary))
            setTextColor(Color.parseColor(currentTheme.textPrimary))
            textSize = 14.5f
            background = null
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { sendTypedText(); true } else false
            }
        }

        val sendBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_send_up)
            background = roundedBg("#FAFAFA", 18)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginStart = dp(6)
            }
            setOnClickListener { sendTypedText() }
        }

        pillInputBox.addView(plusBtn)
        pillInputBox.addView(commandInput)
        pillInputBox.addView(sendBtn)

        bottomComposer.addView(plusToolsDrawer)
        bottomComposer.addView(activeToolBadgeRow)
        bottomComposer.addView(imagePreviewBar)
        bottomComposer.addView(pillInputBox)

        mainContent.addView(topBar)
        mainContent.addView(statusBanner)
        mainContent.addView(centerStage)
        mainContent.addView(bottomComposer)

        // Navigation Drawer (Chat History + Settings + Account)
        val drawerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(currentTheme.bgHeader))
            layoutDirection = dir
            layoutParams = DrawerLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT, GravityCompat.START)
        }
        val drawerTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor(currentTheme.bgRoot))
            setPadding(dp(16), dp(18), dp(16), dp(16))
        }
        val drawerTitle = TextView(this).apply {
            text = "VEXA AI"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(currentTheme.textPrimary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val newChatBtn = TextView(this).apply {
            text = t("new_chat")
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#09090B"))
            background = roundedBg(currentTheme.accent, 18)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { startNewConversation(); drawerLayout.closeDrawer(GravityCompat.START) }
        }
        drawerTop.addView(drawerTitle)
        drawerTop.addView(newChatBtn)

        val histLbl = TextView(this).apply {
            text = t("history_title")
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(currentTheme.textSecondary))
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }

        val drawerScroll = ScrollView(this).apply {
            setPadding(dp(12), 0, dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        drawerSessionsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        drawerScroll.addView(drawerSessionsContainer)

        val u = accounts.getCurrentUser()
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor(currentTheme.bgRoot))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val fText = TextView(this).apply {
            text = "${u?.fullName ?: "Vexa User"} • ${t("verified_user")}"
            textSize = 12.5f
            setTextColor(Color.parseColor("#10A37F"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val logoutBtn = TextView(this).apply {
            text = t("logout")
            textSize = 11.5f
            setTextColor(Color.parseColor("#F87171"))
            setOnClickListener {
                accounts.logout()
                drawerLayout.closeDrawer(GravityCompat.START)
                showAuthScreen()
            }
        }
        footer.addView(fText)
        footer.addView(logoutBtn)

        drawerPanel.addView(drawerTop)
        drawerPanel.addView(histLbl)
        drawerPanel.addView(drawerScroll)
        drawerPanel.addView(footer)

        drawerLayout.addView(mainContent)
        drawerLayout.addView(drawerPanel)
        setContentView(drawerLayout)
    }

    private fun selectToolMode(mode: CommandProcessor.ToolMode) {
        selectedToolMode = mode
        when (mode) {
            CommandProcessor.ToolMode.AUTO -> activeToolBadgeRow.visibility = View.GONE
            CommandProcessor.ToolMode.IMAGE -> {
                activeToolLabel.text = t("mode_image")
                activeToolBadgeRow.visibility = View.VISIBLE
            }
            CommandProcessor.ToolMode.VIDEO -> {
                activeToolLabel.text = t("mode_video")
                activeToolBadgeRow.visibility = View.VISIBLE
            }
            CommandProcessor.ToolMode.SEARCH -> {
                activeToolLabel.text = t("mode_search")
                activeToolBadgeRow.visibility = View.VISIBLE
            }
        }
    }

    private fun showLanguagePickerDialog() {
        val langs = AppStringsAndThemes.SUPPORTED_UI_LANGUAGES
        val names = langs.map { it.displayName }.toTypedArray()
        val cur = langs.indexOfFirst { it.code == uiLangCode }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("🌍 Language / زبان")
            .setSingleChoiceItems(names, cur) { dialog, which ->
                uiLangCode = langs[which].code
                prefs.edit().putString("ui_lang_code", uiLangCode).apply()
                dialog.dismiss()
                val saved = if (::currentSession.isInitialized) currentSession.messages.toList() else emptyList()
                buildChatGptStyleUi()
                for (m in saved) {
                    val s = try { ChatMessage.Sender.valueOf(m.senderName) } catch (_: Exception) { ChatMessage.Sender.VEXA }
                    chatAdapter.addMessage(ChatMessage(m.text, s, m.time))
                }
                updateHeroVisibility()
            }
            .setNegativeButton(t("close"), null)
            .show()
    }

    private fun showSettingsDialog() {
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(12))
            layoutDirection = if (currentLangObj().isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }

        val themes = AppStringsAndThemes.AppTheme.values()
        val themeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, themes.map { "${it.titleFa} (${it.titleEn})" })
            setSelection(themes.indexOf(currentTheme).coerceAtLeast(0))
        }
        val nickInput = EditText(this).apply {
            hint = "نام مستعار"
            setText(memoryStore.getUserNickname())
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        val profInput = EditText(this).apply {
            hint = "شغل و علایق"
            setText(memoryStore.getUserProfession())
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        val rulesInput = EditText(this).apply {
            hint = "دستورالعمل‌های شخصی‌سازی"
            setText(memoryStore.getCustomInstructions())
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            minLines = 3
        }
        val gwInput = EditText(this).apply {
            hint = VexaCoreClient.DEFAULT_GATEWAY_URL
            setText(prefs.getString("gateway_url", ""))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        box.addView(themeSpinner)
        box.addView(nickInput)
        box.addView(profInput)
        box.addView(rulesInput)
        box.addView(gwInput)
        scroll.addView(box)

        AlertDialog.Builder(this)
            .setTitle(t("settings_title"))
            .setView(scroll)
            .setPositiveButton(t("save")) { _, _ ->
                memoryStore.setUserNickname(nickInput.text.toString())
                memoryStore.setUserProfession(profInput.text.toString())
                memoryStore.setCustomInstructions(rulesInput.text.toString())
                prefs.edit().putString("gateway_url", gwInput.text.toString().trim()).apply()
                val chosen = themes[themeSpinner.selectedItemPosition]
                if (chosen != currentTheme) {
                    currentTheme = chosen
                    prefs.edit().putString("app_theme_id", currentTheme.id).apply()
                    val saved = if (::currentSession.isInitialized) currentSession.messages.toList() else emptyList()
                    buildChatGptStyleUi()
                    for (m in saved) {
                        val s = try { ChatMessage.Sender.valueOf(m.senderName) } catch (_: Exception) { ChatMessage.Sender.VEXA }
                        chatAdapter.addMessage(ChatMessage(m.text, s, m.time))
                    }
                    updateHeroVisibility()
                }
                Toast.makeText(this, "ذخیره شد", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(t("close"), null)
            .show()
    }

    private fun updateHeroVisibility() {
        emptyHeroBox.visibility = if (chatAdapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    private fun loadInitialSession() {
        val sessions = historyStore.getAllSessions()
        if (sessions.isNotEmpty()) openSession(sessions.first()) else startNewConversation()
    }

    private fun startNewConversation() {
        vexaCore.clear()
        currentSession = historyStore.createNewSession(t("new_chat").removePrefix("+ "))
        chatAdapter.clearAll()
        updateHeroVisibility()
        refreshDrawerHistoryList()
    }

    private fun openSession(session: ChatHistoryStore.ChatSession) {
        currentSession = session
        chatAdapter.clearAll()
        vexaCore.restoreHistory(session.messages)
        for (m in session.messages) {
            val s = try { ChatMessage.Sender.valueOf(m.senderName) } catch (_: Exception) { ChatMessage.Sender.VEXA }
            chatAdapter.addMessage(ChatMessage(m.text, s, m.time))
        }
        updateHeroVisibility()
        refreshDrawerHistoryList()
    }

    private fun refreshDrawerHistoryList() {
        drawerSessionsContainer.removeAllViews()
        for (s in historyStore.getAllSessions()) {
            val sel = ::currentSession.isInitialized && s.id == currentSession.id
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = if (sel) roundedBg(currentTheme.bgCard, 10, currentTheme.accent, 1) else roundedBg(currentTheme.bgRoot, 10)
                setPadding(dp(11), dp(9), dp(8), dp(9))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
            }
            val tv = TextView(this).apply {
                text = s.title
                textSize = 12.5f
                setTextColor(Color.parseColor(if (sel) currentTheme.accent else currentTheme.textPrimary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { openSession(s); drawerLayout.closeDrawer(GravityCompat.START) }
            }
            val del = TextView(this).apply {
                text = "✕"
                textSize = 13f
                setTextColor(Color.parseColor(currentTheme.textSecondary))
                setOnClickListener {
                    historyStore.deleteSession(s.id)
                    if (::currentSession.isInitialized && s.id == currentSession.id) loadInitialSession() else refreshDrawerHistoryList()
                }
            }
            row.addView(tv)
            row.addView(del)
            drawerSessionsContainer.addView(row)
        }
    }

    private fun openCamera() {
        if (!granted(Manifest.permission.CAMERA)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 2)
            return
        }
        try { cameraLauncher.launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE)) } catch (_: Exception) { }
    }

    private fun openGallery() {
        try { galleryLauncher.launch(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)) } catch (_: Exception) { }
    }

    private fun attachImage(bmp: Bitmap) {
        pendingImage = bmp
        imagePreviewThumb.setImageBitmap(bmp)
        imagePreviewBar.visibility = View.VISIBLE
    }

    private fun clearAttachedImage() {
        pendingImage = null
        imagePreviewThumb.setImageDrawable(null)
        imagePreviewBar.visibility = View.GONE
    }

    private fun appendMessage(text: String, sender: ChatMessage.Sender, image: Bitmap? = null, generatedFile: FileGenerator.GeneratedFile? = null) {
        val tm = timeFmt.format(Date())
        chatAdapter.addMessage(ChatMessage(text, sender, tm, image, generatedFile))
        updateHeroVisibility()
        chatRecycler.post { if (chatAdapter.itemCount > 0) chatRecycler.scrollToPosition(chatAdapter.itemCount - 1) }
        if (::currentSession.isInitialized && sender != ChatMessage.Sender.SYSTEM && text.isNotBlank()) {
            if (sender == ChatMessage.Sender.USER && currentSession.messages.none { it.senderName == "USER" }) {
                currentSession.title = text.replace("\n", " ").take(34)
            }
            currentSession.messages.add(ChatHistoryStore.StoredMessage(text, sender.name, tm))
            historyStore.saveSession(currentSession)
        }
    }

    private fun sendTypedText() {
        val txt = commandInput.text.toString().trim()
        val img = pendingImage
        if (txt.isEmpty() && img == null) return
        val mode = selectedToolMode
        commandInput.setText("")
        plusToolsDrawer.visibility = View.GONE
        clearAttachedImage()

        val displayMsg = txt.ifEmpty { "Analyze this image." }
        appendMessage(displayMsg, ChatMessage.Sender.USER, img)
        statusBanner.visibility = View.VISIBLE

        processor.process(displayMsg, image = img, toolMode = mode) { res ->
            statusBanner.visibility = View.GONE
            if (res.text.isNotBlank() || res.generatedImage != null || res.generatedFile != null) {
                appendMessage(res.text, ChatMessage.Sender.VEXA, res.generatedImage, res.generatedFile)
            }
        }
    }

    private fun showPersonaDialog() {
        val personas = VexaCoreClient.Persona.values()
        val titles = personas.map { "${it.titleEn} | ${it.titleFa}" }.toTypedArray()
        val cur = personas.indexOf(vexaCore.getPersona()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Vexa AI Mode")
            .setSingleChoiceItems(titles, cur) { dialog, which ->
                vexaCore.setPersona(personas[which])
                personaChip.text = "${personas[which].titleEn} ▾"
                dialog.dismiss()
            }
            .setNegativeButton(t("close"), null)
            .show()
    }
}
