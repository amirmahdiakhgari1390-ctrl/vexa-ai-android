package com.vexa.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class ChatHistoryStore(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("vexa_chat_sessions", Context.MODE_PRIVATE)
    private val dateFmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

    data class StoredMessage(val text: String, val senderName: String, val time: String)
    data class ChatSession(val id: String, var title: String, var updatedAt: String, val messages: MutableList<StoredMessage>)

    fun getAllSessions(): MutableList<ChatSession> {
        val raw = prefs.getString("sessions_json", "[]") ?: "[]"
        val out = mutableListOf<ChatSession>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val msgsArr = obj.optJSONArray("messages") ?: JSONArray()
                val msgs = mutableListOf<StoredMessage>()
                for (j in 0 until msgsArr.length()) {
                    val m = msgsArr.getJSONObject(j)
                    msgs.add(StoredMessage(m.optString("text"), m.optString("sender"), m.optString("time")))
                }
                out.add(ChatSession(obj.optString("id"), obj.optString("title", "New Chat"), obj.optString("updatedAt", ""), msgs))
            }
        } catch (_: Exception) { }
        return out
    }

    fun createNewSession(defaultTitle: String = "گفتگوی جدید"): ChatSession {
        val s = ChatSession(UUID.randomUUID().toString(), defaultTitle, dateFmt.format(Date()), mutableListOf())
        val all = getAllSessions()
        all.add(0, s)
        saveAll(all)
        return s
    }

    fun saveSession(session: ChatSession) {
        session.updatedAt = dateFmt.format(Date())
        val all = getAllSessions()
        val idx = all.indexOfFirst { it.id == session.id }
        if (idx >= 0) all.removeAt(idx)
        all.add(0, session)
        while (all.size > 40) all.removeAt(all.size - 1)
        saveAll(all)
    }

    fun deleteSession(sessionId: String) {
        val all = getAllSessions()
        all.removeAll { it.id == sessionId }
        saveAll(all)
    }

    private fun saveAll(sessions: List<ChatSession>) {
        val arr = JSONArray()
        for (s in sessions) {
            val msgsArr = JSONArray()
            for (m in s.messages) {
                msgsArr.put(JSONObject().put("text", m.text).put("sender", m.senderName).put("time", m.time))
            }
            arr.put(JSONObject().put("id", s.id).put("title", s.title).put("updatedAt", s.updatedAt).put("messages", msgsArr))
        }
        prefs.edit().putString("sessions_json", arr.toString()).apply()
    }
}
