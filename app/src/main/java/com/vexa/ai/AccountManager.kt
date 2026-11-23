package com.vexa.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.random.Random

class AccountManager(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("vexa_accounts_db", Context.MODE_PRIVATE)

    data class UserAccount(
        val fullName: String,
        val phone: String,
        val passwordHash: String
    )

    fun isLoggedIn(): Boolean = !prefs.getString("current_user_phone", "").isNullOrEmpty()

    fun getCurrentUser(): UserAccount? {
        val p = prefs.getString("current_user_phone", "").orEmpty()
        return if (p.isEmpty()) null else findByPhone(p)
    }

    fun generateStrongPassword(): String {
        val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val lower = "abcdefghijkmnopqrstuvwxyz"
        val digits = "23456789"
        val symbols = "@#$%&"
        val all = upper + lower + digits + symbols
        val chars = mutableListOf(
            upper[Random.nextInt(upper.length)],
            lower[Random.nextInt(lower.length)],
            digits[Random.nextInt(digits.length)],
            symbols[Random.nextInt(symbols.length)]
        )
        repeat(6) { chars.add(all[Random.nextInt(all.length)]) }
        chars.shuffle()
        return chars.joinToString("")
    }

    fun normalizePhone(raw: String): String {
        val sb = StringBuilder()
        for (c in raw.trim()) {
            when (c) {
                in '۰'..'۹' -> sb.append('0' + (c - '۰'))
                in '٠'..'٩' -> sb.append('0' + (c - '٠'))
                in '0'..'9' -> sb.append(c)
                '+' -> if (sb.isEmpty()) sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun hashPassword(pass: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(pass.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun postOtpNotification(phone: String, otp: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val chId = "vexa_ai_otp"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(chId, "VEXA AI OTP", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val n = NotificationCompat.Builder(ctx, chId)
                .setSmallIcon(R.drawable.ic_vexa_logo)
                .setContentTitle("VEXA AI")
                .setContentText("کد تایید ۵ رقمی برای $phone: $otp")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            nm.notify(90516, n)
        } catch (_: Exception) { }
    }

    fun sendVerificationOtp(rawName: String, rawPhone: String, rawPassword: String): Triple<Boolean, String, Boolean> {
        val name = rawName.trim()
        val phone = normalizePhone(rawPhone)
        val pass = rawPassword.trim()
        if (name.length < 2) return Triple(false, "لطفاً نام و نام خانوادگی را وارد کنید.", false)
        if (phone.length < 10) return Triple(false, "شماره تلفن معتبر نیست.", false)
        if (pass.length < 6) return Triple(false, "رمز عبور باید حداقل ۶ کاراکتر باشد.", false)
        if (getAllUsers().any { it.phone == phone }) {
            return Triple(false, "این شماره قبلاً ثبت شده است؛ وارد شوید.", false)
        }
        val otp = Random.nextInt(10000, 100000).toString()
        prefs.edit()
            .putString("pending_name", name)
            .putString("pending_phone", phone)
            .putString("pending_pass_hash", hashPassword(pass))
            .putString("pending_otp", otp)
            .putLong("pending_otp_time", System.currentTimeMillis())
            .apply()
        postOtpNotification(phone, otp)
        return Triple(true, "VEXA AI — کد تایید ۵ رقمی شماره $phone: $otp", true)
    }

    fun verifyOtpAndCompleteRegistration(rawOtp: String): Pair<Boolean, String> {
        val code = normalizePhone(rawOtp)
        val saved = prefs.getString("pending_otp", "").orEmpty()
        val t0 = prefs.getLong("pending_otp_time", 0L)
        if (saved.isEmpty()) return false to "ابتدا دکمه ارسال کد تایید را بزنید."
        if (System.currentTimeMillis() - t0 > 10 * 60 * 1000L) return false to "کد منقضی شده است."
        if (code != saved) return false to "کد ۵ رقمی اشتباه است."

        val name = prefs.getString("pending_name", "").orEmpty()
        val phone = prefs.getString("pending_phone", "").orEmpty()
        val passHash = prefs.getString("pending_pass_hash", "").orEmpty()

        val all = getAllUsers()
        all.add(UserAccount(name, phone, passHash))
        saveAllUsers(all)

        prefs.edit()
            .remove("pending_otp")
            .remove("pending_name")
            .remove("pending_phone")
            .remove("pending_pass_hash")
            .putString("current_user_phone", phone)
            .apply()
        return true to "ثبت‌نام با موفقیت انجام شد. خوش آمدید $name عزیز!"
    }

    fun login(rawPhone: String, rawPassword: String): Pair<Boolean, String> {
        val phone = normalizePhone(rawPhone)
        val pass = rawPassword.trim()
        val user = findByPhone(phone) ?: return false to "حسابی با این شماره پیدا نشد."
        if (user.passwordHash != hashPassword(pass)) return false to "رمز عبور اشتباه است."
        prefs.edit().putString("current_user_phone", phone).apply()
        return true to "خوش آمدید ${user.fullName} عزیز!"
    }

    fun logout() = prefs.edit().remove("current_user_phone").apply()

    private fun findByPhone(phone: String): UserAccount? = getAllUsers().firstOrNull { it.phone == phone }

    private fun getAllUsers(): MutableList<UserAccount> {
        val raw = prefs.getString("users_list", "[]") ?: "[]"
        val out = mutableListOf<UserAccount>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(UserAccount(o.optString("name"), o.optString("phone"), o.optString("pass")))
            }
        } catch (_: Exception) { }
        return out
    }

    private fun saveAllUsers(users: List<UserAccount>) {
        val arr = JSONArray()
        for (u in users) {
            arr.put(JSONObject().put("name", u.fullName).put("phone", u.phone).put("pass", u.passwordHash))
        }
        prefs.edit().putString("users_list", arr.toString()).apply()
    }
}
