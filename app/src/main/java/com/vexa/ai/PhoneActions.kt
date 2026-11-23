package com.vexa.ai

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class PhoneActions(private val ctx: Context) {

    private var torchOn = false
    private data class AppInfo(val label: String, val pkg: String)

    private fun fold(s: String) =
        s.lowercase(Locale.ROOT).replace('\u200c', ' ').replace('ي', 'ی').replace('ك', 'ک').trim()

    private fun launch(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private fun installedApps(): List<AppInfo> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0).map {
            AppInfo(fold(it.loadLabel(pm).toString()), it.activityInfo.packageName)
        }
    }

    fun openApp(name: String, isFa: Boolean): String {
        val n = fold(name)
        val app = installedApps().firstOrNull { it.label == n || it.label.contains(n) }
            ?: return if (isFa) "برنامه‌ای با نام «$name» پیدا نشد." else "App \"$name\" not found."
        val intent = ctx.packageManager.getLaunchIntentForPackage(app.pkg)
            ?: return if (isFa) "امکان اجرای «${app.label}» وجود ندارد." else "Cannot launch ${app.label}."
        return if (launch(intent)) {
            if (isFa) "در حال باز کردن ${app.label}..." else "Opening ${app.label}..."
        } else {
            if (isFa) "خطا در باز کردن ${app.label}." else "Failed to open ${app.label}."
        }
    }

    fun torch(on: Boolean?, isFa: Boolean): String = try {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return if (isFa) "این دستگاه چراغ قوه ندارد." else "No flashlight available."
        torchOn = on ?: !torchOn
        cm.setTorchMode(id, torchOn)
        if (torchOn) {
            if (isFa) "چراغ قوه روشن شد." else "Flashlight turned on."
        } else {
            if (isFa) "چراغ قوه خاموش شد." else "Flashlight turned off."
        }
    } catch (_: Exception) {
        if (isFa) "خطا در کنترل چراغ قوه." else "Flashlight error."
    }

    fun volume(direction: Int, isFa: Boolean): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when (direction) {
            1 -> {
                repeat(3) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
                if (isFa) "صدا زیاد شد." else "Volume up."
            }
            -1 -> {
                repeat(3) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
                if (isFa) "صدا کم شد." else "Volume down."
            }
            2 -> {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), AudioManager.FLAG_SHOW_UI)
                if (isFa) "صدا روی حداکثر تنظیم شد." else "Volume max."
            }
            else -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                if (isFa) "صدا قطع شد." else "Audio muted."
            }
        }
    }

    fun setRingerMode(silent: Boolean, isFa: Boolean): String = try {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.ringerMode = if (silent) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL
        if (silent) {
            if (isFa) "گوشی روی حالت ویبره رفت." else "Vibrate mode enabled."
        } else {
            if (isFa) "حالت عادی صدا فعال شد." else "Normal ringer enabled."
        }
    } catch (_: Exception) {
        if (isFa) "تغییر حالت زنگ نیازمند مجوز است." else "Permission required."
    }

    fun battery(isFa: Boolean): String {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        if (level < 0) return if (isFa) "وضعیت باتری در دسترس نیست." else "Battery info unavailable."
        val pct = level * 100 / scale
        return if (isFa) "شارژ باتری $pct درصد است." else "Battery is at $pct%."
    }

    fun openWifiPanel(isFa: Boolean): String =
        if (launch(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))) {
            if (isFa) "تنظیمات اینترنت باز شد." else "Internet panel opened."
        } else {
            if (isFa) "خطا در باز کردن تنظیمات." else "Failed to open settings."
        }

    fun openBluetoothSettings(isFa: Boolean): String =
        if (launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) {
            if (isFa) "تنظیمات بلوتوث باز شد." else "Bluetooth settings opened."
        } else {
            if (isFa) "خطا در باز کردن بلوتوث." else "Failed to open Bluetooth."
        }

    fun openDisplaySettings(isFa: Boolean): String =
        if (launch(Intent(Settings.ACTION_DISPLAY_SETTINGS))) {
            if (isFa) "تنظیمات نمایشگر باز شد." else "Display settings opened."
        } else {
            if (isFa) "خطا در باز کردن نمایشگر." else "Failed to open display settings."
        }

    private fun resolveTarget(target: String): Pair<String, String>? {
        val digits = target.replace(" ", "").replace("-", "")
        if (digits.length >= 5 && digits.all { it.isDigit() || it == '+' }) return Pair(digits, digits)
        if (!granted(Manifest.permission.READ_CONTACTS)) return null
        val c = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%${target.trim()}%"),
            null
        )
        c?.use { if (it.moveToFirst()) return Pair(it.getString(0), it.getString(1)) }
        return null
    }

    fun call(target: String, isFa: Boolean): String {
        val (label, number) = resolveTarget(target)
            ?: return if (isFa) "مخاطب «$target» یافت نشد." else "Contact \"$target\" not found."
        val action = if (granted(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return if (launch(Intent(action, Uri.parse("tel:" + Uri.encode(number))))) {
            if (isFa) "در حال تماس با $label..." else "Calling $label..."
        } else {
            if (isFa) "خطا در برقراری تماس." else "Call failed."
        }
    }

    fun sendSms(target: String, message: String, isFa: Boolean): String {
        val (label, number) = resolveTarget(target)
            ?: return if (isFa) "مخاطب «$target» یافت نشد." else "Contact \"$target\" not found."
        if (message.isBlank()) {
            return if (launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))))) {
                if (isFa) "صفحه پیامک برای $label باز شد." else "SMS screen opened for $label."
            } else {
                if (isFa) "خطا در باز کردن پیامک." else "SMS failed."
            }
        }
        return try {
            if (granted(Manifest.permission.SEND_SMS)) {
                @Suppress("DEPRECATION")
                SmsManager.getDefault().sendTextMessage(number, null, message, null, null)
                if (isFa) "پیامک برای $label ارسال شد." else "SMS sent to $label."
            } else {
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).apply {
                    putExtra("sms_body", message)
                }
                launch(i)
                if (isFa) "متن پیامک برای $label آماده شد." else "SMS draft ready for $label."
            }
        } catch (_: Exception) {
            if (isFa) "خطا در ارسال پیامک." else "Failed to send SMS."
        }
    }

    fun sendWhatsApp(target: String, message: String, isFa: Boolean): String {
        val (label, rawNum) = resolveTarget(target)
            ?: return if (isFa) "مخاطب «$target» یافت نشد." else "Contact \"$target\" not found."
        var clean = rawNum.replace(Regex("[^0-9+]"), "")
        if (clean.startsWith("09")) clean = "98" + clean.substring(1)
        clean = clean.removePrefix("+")
        val url = "https://api.whatsapp.com/send?phone=$clean&text=" + Uri.encode(message)
        return if (launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
            if (isFa) "واتساپ برای $label باز شد." else "WhatsApp opened for $label."
        } else {
            if (isFa) "واتساپ نصب نیست." else "WhatsApp not installed."
        }
    }

    fun navigateTo(destination: String, isFa: Boolean): String {
        val webUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(destination))
        return if (launch(Intent(Intent.ACTION_VIEW, webUri))) {
            if (isFa) "مسیر «$destination» در نقشه باز شد." else "Map opened for $destination."
        } else {
            if (isFa) "خطا در باز کردن نقشه." else "Could not open maps."
        }
    }

    fun playYouTube(query: String, isFa: Boolean): String {
        val webUri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))
        return if (launch(Intent(Intent.ACTION_VIEW, webUri))) {
            if (isFa) "جستجوی «$query» در یوتیوب باز شد." else "YouTube search opened for \"$query\"."
        } else {
            if (isFa) "خطا در باز کردن یوتیوب." else "Could not open YouTube."
        }
    }

    fun readClipboard(isFa: Boolean): String {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val txt = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()
        return if (txt.isNullOrEmpty()) {
            if (isFa) "کلیپ‌بورد خالی است." else "Clipboard is empty."
        } else {
            if (isFa) "متن کلیپ‌بورد: $txt" else "Clipboard: $txt"
        }
    }

    fun flipCoin(isFa: Boolean): String {
        val h = Random.nextBoolean()
        return if (isFa) (if (h) "شیر آمد!" else "خط آمد!") else (if (h) "Heads!" else "Tails!")
    }

    fun rollDice(isFa: Boolean): String {
        val n = Random.nextInt(1, 7)
        return if (isFa) "تاس عدد $n را آورد." else "Rolled $n."
    }

    fun randomPassword(len: Int = 12, isFa: Boolean): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789@#%&*"
        val pass = (1..len).map { chars[Random.nextInt(chars.length)] }.joinToString("")
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Vexa Password", pass))
        return if (isFa) "رمز عبور ساخته و کپی شد: $pass" else "Password generated & copied: $pass"
    }

    fun timer(seconds: Int, isFa: Boolean): String {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, 86400))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (launch(i)) {
            if (isFa) "تایمر تنظیم شد." else "Timer set."
        } else {
            if (isFa) "خطا در تنظیم تایمر." else "Could not set timer."
        }
    }

    fun alarm(hour: Int, minute: Int, isFa: Boolean): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (launch(i)) {
            if (isFa) "آلارم برای $hour:$minute تنظیم شد." else String.format(Locale.US, "Alarm set for %02d:%02d.", hour, minute)
        } else {
            if (isFa) "خطا در تنظیم آلارم." else "Could not set alarm."
        }
    }

    private val notesFile get() = File(ctx.filesDir, "vexa_notes.txt")

    fun saveNote(text: String, isFa: Boolean): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        notesFile.appendText("[$stamp] $text\n")
        return if (isFa) "یادداشت ذخیره شد." else "Note saved."
    }

    fun readNotes(isFa: Boolean): String {
        if (!notesFile.exists()) return if (isFa) "یادداشتی ثبت نشده است." else "No notes saved."
        val last = notesFile.readLines().filter { it.isNotBlank() }.takeLast(5)
        if (last.isEmpty()) return if (isFa) "یادداشتی ثبت نشده است." else "No notes saved."
        return if (isFa) "یادداشت‌ها: ${last.joinToString(" | ") { it.substringAfter("] ") }}"
        else "Notes: ${last.joinToString(" | ") { it.substringAfter("] ") }}"
    }
}
