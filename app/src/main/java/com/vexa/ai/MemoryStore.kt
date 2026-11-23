package com.vexa.ai

import android.content.Context
import org.json.JSONArray

class MemoryStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("vexa_training_memory", Context.MODE_PRIVATE)

    fun getCustomInstructions(): String = prefs.getString("custom_instructions", "").orEmpty().trim()
    fun setCustomInstructions(v: String) = prefs.edit().putString("custom_instructions", v.trim()).apply()

    fun getUserNickname(): String = prefs.getString("user_nickname", "").orEmpty().trim()
    fun setUserNickname(v: String) = prefs.edit().putString("user_nickname", v.trim()).apply()

    fun getUserProfession(): String = prefs.getString("user_profession", "").orEmpty().trim()
    fun setUserProfession(v: String) = prefs.edit().putString("user_profession", v.trim()).apply()

    fun getResponseStyle(): String = prefs.getString("response_style", "balanced").orEmpty()
    fun setResponseStyle(v: String) = prefs.edit().putString("response_style", v).apply()

    fun remember(fact: String, isPersian: Boolean = true): String {
        val clean = fact.trim()
        if (clean.isEmpty()) {
            return if (isPersian) "چه نکته‌ای را یاد بگیرم؟" else "What should I remember?"
        }
        val list = getAll().toMutableList()
        if (clean !in list) {
            list.add(clean)
            saveAll(list)
        }
        return if (isPersian) "در حافظه دائمی وِکسا ثبت شد: «$clean»" else "Saved to memory: \"$clean\""
    }

    fun autoLearnFromUserMessage(msg: String) {
        val clean = msg.trim()
        if (clean.length !in 6..240) return
        val triggers = listOf(
            "اسم من ", "من هستم", "شغل من", "علاقه دارم", "یاد بگیر",
            "my name is", "i am ", "i work as", "i prefer", "remember"
        )
        val low = clean.lowercase()
        if (triggers.any { low.contains(it) }) {
            val list = getAll().toMutableList()
            if (list.none { it.equals(clean, ignoreCase = true) }) {
                list.add(clean)
                saveAll(list)
            }
        }
    }

    fun getAll(): List<String> {
        val raw = prefs.getString("facts", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clearAll(isPersian: Boolean = true): String {
        prefs.edit()
            .remove("facts")
            .remove("custom_instructions")
            .remove("user_nickname")
            .remove("user_profession")
            .apply()
        return if (isPersian) "تمامی آموزش‌ها و حافظه پاک شد." else "All training and memory cleared."
    }

    fun asPromptContext(): String {
        val sb = StringBuilder()
        val nick = getUserNickname()
        val prof = getUserProfession()
        if (nick.isNotEmpty() || prof.isNotEmpty()) {
            sb.append("\n[User Profile]:\n")
            if (nick.isNotEmpty()) sb.append("- Name: ").append(nick).append("\n")
            if (prof.isNotEmpty()) sb.append("- Role/Interests: ").append(prof).append("\n")
        }
        val styleRule = when (getResponseStyle()) {
            "friendly" -> "Warm, natural, human tone."
            "formal" -> "Polite, clear, structured tone."
            "concise" -> "Direct, concise, clear."
            "detailed" -> "Thorough, step-by-step, clear."
            else -> "Clear, natural, adaptive."
        }
        sb.append("\n[Style]: ").append(styleRule).append("\n")
        val custom = getCustomInstructions()
        if (custom.isNotEmpty()) {
            sb.append("\n[Custom Rules]:\n").append(custom).append("\n")
        }
        val facts = getAll()
        if (facts.isNotEmpty()) {
            sb.append("\n[Learned Facts]:\n")
            facts.forEach { sb.append("- ").append(it).append("\n") }
        }
        return sb.toString()
    }

    private fun saveAll(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString("facts", arr.toString()).apply()
    }
}
