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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
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
    private lateinit var vexaVoice: VexaVoiceEngine

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var speaking = false
    private var voiceReplyEnabled = true
    private var uiLangCode = "fa-IR"
    private var currentTheme = AppStringsAndThemes.AppTheme.OBSIDIAN_CYAN
    private var activeUntil = 0L
    private val activeWindowMs = 25_000L
    private val handler = Handler(Looper.getMainLooper())
    private var pendingImage: Bitmap? = null

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var drawerSessionsContainer: LinearLayout
    private lateinit var statusBadge: TextView
    private lateinit var personaChip: TextView
    private lateinit var langToggleBtn: TextView
    private lateinit var voiceToggleBtn: TextView
    private lateinit var liveTranscriptView: TextView
    private lateinit var voiceOrb: VoiceOrbView
    private lateinit var micIconView: ImageView
    private lateinit var micHintView: TextView
    private lateinit var chatRecycler: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var commandInput: EditText
    private lateinit var wakeSwitch: Switch
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
        voiceReplyEnabled = prefs.getBoolean("voice_reply", true)
        uiLangCode = prefs.getString("ui_lang_code", "fa-IR") ?: "fa-IR"
        val savedTheme = prefs.getString("app_theme_id", AppStringsAndThemes.AppTheme.OBSIDIAN_CYAN.id)
        currentTheme = AppStringsAndThemes.AppTheme.values().firstOrNull { it.id == savedTheme }
            ?: AppStringsAndThemes.AppTheme.OBSIDIAN_CYAN

        accounts = AccountManager(this)
        memoryStore = MemoryStore(this)
        historyStore = ChatHistoryStore(this)
        vexaCore = VexaCoreClient(prefs, memoryStore)
        processor = CommandProcessor(this, PhoneActions(this), vexaCore, memoryStore, accounts)

        vexaVoice = VexaVoiceEngine(this) { isSpeaking ->
            speaking = isSpeaking
            if (isSpeaking) setStatus("SPEAKING", VoiceOrbView.Mode.SPEAKING)
            else if (listening) resumeListening() else setStatus("ONLINE", VoiceOrbView.Mode.IDLE)
        }

        if (!accounts.isLoggedIn()) showAuthScreen() else launchMainAssistant()
    }

    private fun t(key: String): String = AppStringsAndThemes.tr(uiLangCode, key)
    private fun currentLangObj(): AppStringsAndThemes.WorldLang =
        AppStringsAndThemes.SUPPORTED_UI_LANGUAGES.firstOrNull { it.code == uiLangCode }
            ?: AppStringsAndThemes.SUPPORTED_UI_LANGUAGES.first()

    private fun launchMainAssistant() {
        buildGlobalUiWithDrawer()
        loadInitialSession()
        if (!granted(Manifest.permission.RECORD_AUDIO)) askPermissions()
    }

    override fun onStop() {
        super.onStop()
        if (listening) stopListening("PAUSED")
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer?.destroy()
        vexaVoice.shutdown()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.CAMERA
            ),
            1
        )
    }

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
            setPadding(dp(24), dp(34), dp(24), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_vexa_logo)
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply { bottomMargin = dp(10) }
        }
        val title = TextView(this).apply {
            text = "VEXA AI"
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(currentTheme.textPrimary))
        }
        val subtitle = TextView(this).apply {
            text = "دستیار هوش مصنوعی چندزبانه"
            textSize = 13f
            setTextColor(Color.parseColor(currentTheme.accent))
            setPadding(0, dp(4), 0, dp(20))
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
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor(currentTheme.textPrimary))
            textSize = 14f
            background = roundedBg(currentTheme.bgInput, 12, currentTheme.border, 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        }
        val phoneInput = EditText(this).apply {
            hint = "09123456789"
            inputType = InputType.TYPE_CLASS_PHONE
            setHintTextColor(Color.parseColor("#64748B"))
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
            setHintTextColor(Color.parseColor("#64748B"))
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
            setTextColor(Color.parseColor("#050811"))
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
            background = roundedBg(currentTheme.bgInput, 10, "#059669", 1)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        otpBox.addView(otpNotice)
        otpBox.addView(otpInput)

        val submitBtn = Button(this).apply {
            text = "📩 ارسال کد تایید ۵ رقمی"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#050811"))
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

    private fun buildGlobalUiWithDrawer() {
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

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(10))
            setBackgroundColor(Color.parseColor(currentTheme.bgHeader))
        }

        val menuBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_menu)
            background = roundedBg(currentTheme.bgInput, 12, currentTheme.border, 1)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(8) }
            setOnClickListener {
                refreshDrawerHistoryList()
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        val logoIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_vexa_logo)
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(7) }
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleText = TextView(this).apply {
            text = "VEXA AI"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(currentTheme.textPrimary))
        }

        statusBadge = TextView(this).apply {
            text = "ONLINE"
            textSize = 8.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#34D399"))
            background = roundedBg("#064E3B", 20, "#059669", 1)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(7) }
        }

        titleRow.addView(titleText)
        titleRow.addView(statusBadge)

        personaChip = TextView(this).apply {
            text = "${vexaCore.getPersona().titleEn} ▾"
            textSize = 10f
            setTextColor(Color.parseColor(currentTheme.accent))
            setPadding(0, dp(2), 0, 0)
            setOnClickListener { showPersonaDialog() }
        }

        titleCol.addView(titleRow)
        titleCol.addView(personaChip)

        langToggleBtn = TextView(this).apply {
            text = "🌐 ${langObj.shortLabel}"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(currentTheme.textPrimary))
            background = roundedBg(currentTheme.bgInput, 12, currentTheme.border, 1)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginEnd = dp(4) }
            setOnClickListener { showLanguagePickerDialog() }
        }

        voiceToggleBtn = TextView(this).apply {
            text = if (voiceReplyEnabled) "🔊" else "🔇"
            textSize = 13f
            setTextColor(Color.parseColor(currentTheme.textPrimary))
            background = roundedBg(currentTheme.bgInput, 12, currentTheme.border, 1)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginEnd = dp(4) }
            setOnClickListener {
                voiceReplyEnabled = !voiceReplyEnabled
                prefs.edit().putBoolean("voice_reply", voiceReplyEnabled).apply()
                text = if (voiceReplyEnabled) "🔊" else "🔇"
                if (!voiceReplyEnabled) vexaVoice.stop()
            }
        }

        val settingsBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            background = roundedBg(currentTheme.bgInput, 12, currentTheme.border, 1)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener { showSettingsDialog() }
        }

        topBar.addView(menuBtn)
        topBar.addView(logoIcon)
        topBar.addView(titleCol)
        topBar.addView(langToggleBtn)
        topBar.addView(voiceToggleBtn)
        topBar.addView(settingsBtn)

        val orbStage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(6))
            setBackgroundColor(Color.parseColor(currentTheme.bgRoot))
        }
        val orbContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96))
            setOnClickListener { toggleListening() }
        }
        voiceOrb = VoiceOrbView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        micIconView = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)
        }
        orbContainer.addView(voiceOrb)
        orbContainer.addView(micIconView)

        micHintView = TextView(this).apply {
            text = t("tap_to_speak")
            textSize = 11.5f
            setTextColor(Color.parseColor(currentTheme.textSecondary))
            setPadding(0, dp(3), 0, dp(2))
        }
        liveTranscriptView = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(Color.parseColor(currentTheme.accent))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            visibility = View.GONE
        }
        orbStage.addView(orbContainer)
        orbStage.addView(micHintView)
        orbStage.addView(liveTranscriptView)

        chatAdapter = ChatAdapter(mutableListOf())
        chatRecycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply { stackFromEnd = true }
            adapter = chatAdapter
            setPadding(dp(14), dp(8), dp(14), dp(8))
            clipToPadding = false
        }

        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = listOf(
            t("chip_video") to t("chip_video_cmd"),
            t("chip_photo") to t("chip_photo_cmd"),
            t("chip_train") to "__TRAIN__",
            t("chip_theme") to "__THEME__",
            t("chip_btc") to t("chip_btc_cmd"),
            t("chip_pdf") to t("chip_pdf_cmd"),
            t("chip_chart") to "__GALLERY__"
        )
        for ((lbl, cmd) in chips) {
            val tv = TextView(this).apply {
                text = lbl
                textSize = 11.5f
                setTextColor(Color.parseColor(currentTheme.textPrimary))
                background = roundedBg(currentTheme.bgCard, 20, currentTheme.border, 1)
                setPadding(dp(11), dp(6), dp(11), dp(6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
                setOnClickListener {
                    when (cmd) {
                        "__TRAIN__", "__THEME__" -> showSettingsDialog()
                        "__GALLERY__" -> openGallery()
                        else -> submitUserCommand(cmd, speakOut = voiceReplyEnabled)
                    }
                }
            }
            chipRow.addView(tv)
        }
        chipScroll.addView(chipRow)

        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(currentTheme.bgHeader))
            setPadding(dp(10), dp(7), dp(10), dp(10))
        }

        imagePreviewBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(currentTheme.bgCard, 12, currentTheme.accent, 1)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
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
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F87171"))
            setOnClickListener { clearAttachedImage() }
        }
        imagePreviewBar.addView(imagePreviewThumb)
        imagePreviewBar.addView(imgLbl)
        imagePreviewBar.addView(rmImg)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(currentTheme.bgCard, 24, currentTheme.border, 1)
            setPadding(dp(8), dp(4), dp(6), dp(4))
        }
        val camBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_camera)
            background = null
            setPadding(dp(7), dp(7), dp(7), dp(7))
            setOnClickListener { openCamera() }
        }
        val galBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_gallery)
            background = null
            setPadding(dp(7), dp(7), dp(7), dp(7))
            setOnClickListener { openGallery() }
        }
        commandInput = EditText(this).apply {
            hint = t("input_hint")
            setHintTextColor(Color.parseColor(currentTheme.textSecondary))
            setTextColor(Color.parseColor(currentTheme.textPrimary))
            textSize = 14f
            background = null
            maxLines = 3
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { sendTypedText(); true } else false
            }
        }
        val sendBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_send)
            background = roundedBg(currentTheme.accent, 20)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            setOnClickListener { sendTypedText() }
        }
        inputRow.addView(camBtn)
        inputRow.addView(galBtn)
        inputRow.addView(commandInput)
        inputRow.addView(sendBtn)

        val wakeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), 0)
        }
        val wakeLbl = TextView(this).apply {
            text = t("wake_label")
            textSize = 11f
            setTextColor(Color.parseColor(currentTheme.textSecondary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        wakeSwitch = Switch(this).apply {
            isChecked = prefs.getBoolean("need_wake", false)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("need_wake", c).apply() }
        }
        wakeRow.addView(wakeLbl)
        wakeRow.addView(wakeSwitch)

        bottomPanel.addView(imagePreviewBar)
        bottomPanel.addView(inputRow)
        bottomPanel.addView(wakeRow)

        mainContent.addView(topBar)
        mainContent.addView(orbStage)
        mainContent.addView(chatRecycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        mainContent.addView(chipScroll)
        mainContent.addView(bottomPanel)

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
            setTextColor(Color.parseColor("#050811"))
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
            setTextColor(Color.parseColor("#34D399"))
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
                if (listening) { recognizer?.cancel(); startRecognizer() }
                val saved = if (::currentSession.isInitialized) currentSession.messages.toList() else emptyList()
                buildGlobalUiWithDrawer()
                if (saved.isEmpty()) addWelcomeMessage()
                else {
                    for (m in saved) {
                        val s = try { ChatMessage.Sender.valueOf(m.senderName) } catch (_: Exception) { ChatMessage.Sender.VEXA }
                        chatAdapter.addMessage(ChatMessage(m.text, s, m.time))
                    }
                }
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
            hint = "دستورالعمل‌های نامحدود"
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
                    buildGlobalUiWithDrawer()
                    for (m in saved) {
                        val s = try { ChatMessage.Sender.valueOf(m.senderName) } catch (_: Exception) { ChatMessage.Sender.VEXA }
                        chatAdapter.addMessage(ChatMessage(m.text, s, m.time))
                    }
                }
                Toast.makeText(this, "ذخیره شد", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(t("close"), null)
            .show()
    }

    private fun loadInitialSession() {
        val sessions = historyStore.getAllSessions()
        if (sessions.isNotEmpty()) openSession(sessions.first()) else startNewConversation()
    }

    private fun startNewConversation() {
        vexaVoice.stop()
        vexaCore.clear()
        currentSession = historyStore.createNewSession(t("new_chat").removePrefix("+ "))
        chatAdapter.clearAll()
        addWelcomeMessage()
        refreshDrawerHistoryList()
    }

    private fun openSession(session: ChatHistoryStore.ChatSession) {
        vexaVoice.stop()
        currentSession = session
        chatAdapter.clearAll()
        vexaCore.restoreHistory(session.messages)
        if (session.messages.isEmpty()) addWelcomeMessage()
        else {
            for (m in session.messages) {
                val s = try { ChatMessage.Sender.valueOf(m.senderName) } catch (_: Exception) { ChatMessage.Sender.VEXA }
                chatAdapter.addMessage(ChatMessage(m.text, s, m.time))
            }
        }
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

    private fun addWelcomeMessage() {
        val name = accounts.getCurrentUser()?.fullName ?: "Friend"
        chatAdapter.addMessage(ChatMessage(String.format(t("welcome_body"), name), ChatMessage.Sender.VEXA, timeFmt.format(Date())))
    }

    private fun appendMessage(text: String, sender: ChatMessage.Sender, image: Bitmap? = null, generatedFile: FileGenerator.GeneratedFile? = null) {
        val tm = timeFmt.format(Date())
        chatAdapter.addMessage(ChatMessage(text, sender, tm, image, generatedFile))
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
        commandInput.setText("")
        clearAttachedImage()
        submitUserCommand(txt.ifEmpty { "Analyze this image." }, speakOut = voiceReplyEnabled, image = img)
    }

    private fun setStatus(status: String, orbMode: VoiceOrbView.Mode) {
        statusBadge.text = status
        voiceOrb.mode = orbMode
        when (orbMode) {
            VoiceOrbView.Mode.IDLE -> {
                micIconView.setImageResource(R.drawable.ic_mic)
                micHintView.text = t("tap_to_speak")
                liveTranscriptView.visibility = View.GONE
            }
            VoiceOrbView.Mode.LISTENING -> {
                micIconView.setImageResource(R.drawable.ic_stop)
                micHintView.text = t("listening")
            }
            VoiceOrbView.Mode.THINKING -> {
                micHintView.text = t("thinking")
                liveTranscriptView.visibility = View.GONE
            }
            VoiceOrbView.Mode.SPEAKING -> {
                micHintView.text = t("speaking")
                liveTranscriptView.visibility = View.GONE
            }
        }
    }

    private fun showPersonaDialog() {
        val personas = VexaCoreClient.Persona.values()
        val titles = personas.map { "${it.titleEn} | ${it.titleFa}" }.toTypedArray()
        val cur = personas.indexOf(vexaCore.getPersona()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Mode")
            .setSingleChoiceItems(titles, cur) { dialog, which ->
                vexaCore.setPersona(personas[which])
                personaChip.text = "${personas[which].titleEn} ▾"
                dialog.dismiss()
            }
            .setNegativeButton(t("close"), null)
            .show()
    }

    private fun speak(text: String) {
        if (!voiceReplyEnabled || text.isBlank()) {
            speaking = false
            if (listening) resumeListening() else setStatus("ONLINE", VoiceOrbView.Mode.IDLE)
            return
        }
        vexaVoice.speak(text, preferredLangTag = uiLangCode)
    }

    private fun toggleListening() {
        if (listening) { stopListening("ONLINE"); return }
        if (!granted(Manifest.permission.RECORD_AUDIO)) { askPermissions(); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        listening = true
        setStatus("LISTENING", VoiceOrbView.Mode.LISTENING)
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(listener) }
        }
        startRecognizer()
    }

    private fun stopListening(msg: String) {
        listening = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        vexaVoice.stop()
        speaking = false
        voiceOrb.rmsLevel = 0f
        setStatus(msg, VoiceOrbView.Mode.IDLE)
    }

    private fun startRecognizer() {
        if (!listening || speaking) return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, uiLangCode)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer?.startListening(i)
    }

    private fun resumeListening(delayMs: Long = 300) {
        if (!listening || speaking) return
        handler.postDelayed({ startRecognizer() }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = setStatus("LISTENING", VoiceOrbView.Mode.LISTENING)
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { voiceOrb.rmsLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { voiceOrb.rmsLevel = 0f }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                if (it.isNotBlank()) {
                    liveTranscriptView.visibility = View.VISIBLE
                    liveTranscriptView.text = "« $it … »"
                }
            }
        }
        override fun onResults(results: Bundle?) {
            liveTranscriptView.visibility = View.GONE
            voiceOrb.rmsLevel = 0f
            val txt = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (txt.isNullOrBlank()) resumeListening() else handleSpeech(txt)
        }
        override fun onError(error: Int) {
            voiceOrb.rmsLevel = 0f
            if (listening) resumeListening(600)
        }
    }

    private fun handleSpeech(text: String) {
        val (body, heard) = processor.stripWake(processor.norm(text))
        val now = SystemClock.elapsedRealtime()
        if (prefs.getBoolean("need_wake", false) && !heard && now > activeUntil) {
            resumeListening()
            return
        }
        if (heard) activeUntil = now + activeWindowMs
        val img = pendingImage
        if (img != null) clearAttachedImage()
        appendMessage(text, ChatMessage.Sender.USER, img)
        setStatus("THINKING", VoiceOrbView.Mode.THINKING)
        processor.process(body.ifBlank { text }, image = img) { res -> respond(res, speakOut = true) }
    }

    private fun submitUserCommand(text: String, speakOut: Boolean, image: Bitmap? = null) {
        val (body, _) = processor.stripWake(processor.norm(text))
        appendMessage(text, ChatMessage.Sender.USER, image)
        setStatus("THINKING", VoiceOrbView.Mode.THINKING)
        processor.process(if (image != null) text else body.ifBlank { text }, image = image) { res ->
            respond(res, speakOut = speakOut)
        }
    }

    private fun respond(res: CommandProcessor.ProcessResponse, speakOut: Boolean) {
        if (res.text.isNotBlank() || res.generatedImage != null || res.generatedFile != null) {
            appendMessage(res.text, ChatMessage.Sender.VEXA, res.generatedImage, res.generatedFile)
        }
        val toSpeak = res.speakText ?: res.text
        if (speakOut && voiceReplyEnabled) speak(toSpeak) else setStatus("ONLINE", VoiceOrbView.Mode.IDLE)
    }
}
