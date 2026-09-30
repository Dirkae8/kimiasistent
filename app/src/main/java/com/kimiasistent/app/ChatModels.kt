package com.kimiasistent.app

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject

class ChatMsg(
    val isUser: Boolean,
    var text: String,
    val image: Bitmap? = null,
    var pending: Boolean = false
)

object ChatStore {
    val messages = mutableListOf<ChatMsg>()
    private const val PREF = "chat_history"

    fun load(ctx: Context) {
        messages.clear()
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = JSONArray(p.getString("messages", "[]") ?: "[]")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            messages.add(ChatMsg(o.optBoolean("u", false), o.optString("t", "")))
        }
    }

    fun save(ctx: Context) {
        val arr = JSONArray()
        for (m in messages) {
            if (m.pending) continue
            val o = JSONObject()
            o.put("u", m.isUser)
            o.put("t", m.text)
            arr.put(o)
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString("messages", arr.toString()).apply()
    }

    fun add(m: ChatMsg) {
        messages.add(m)
    }

    fun clear() {
        messages.clear()
    }
}
