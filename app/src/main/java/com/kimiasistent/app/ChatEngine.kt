package com.kimiasistent.app

import android.content.Context

/** Отправка истории в Kimi API c индикатором «печатает…» — общий для чата и панели ассистента. */
object ChatEngine {

    fun buildHistory(): List<KimiClient.ApiMsg> {
        val msgs = ChatStore.messages.filter { !it.pending }
        val lastImgIdx = msgs.indexOfLast { it.isUser && it.image != null }
        val start = maxOf(0, msgs.size - 12)
        val out = ArrayList<KimiClient.ApiMsg>()
        for (i in start until msgs.size) {
            val m = msgs[i]
            val img = if (i == lastImgIdx && m.image != null) m.image else null
            val txt = if (img == null && m.image != null) "[изображение] ${m.text}" else m.text
            out.add(KimiClient.ApiMsg(if (m.isUser) "user" else "assistant", txt, img))
        }
        return out
    }

    fun send(ctx: Context, onSettled: () -> Unit) {
        ChatStore.add(ChatMsg(false, "", null, true))
        val hist = buildHistory()
        KimiClient.chat(ctx, hist) { ok, result ->
            val p = ChatStore.messages.lastOrNull { it.pending }
            if (p != null) {
                p.pending = false
                p.text = if (ok) result else "⚠️ $result"
            }
            ChatStore.save(ctx)
            onSettled()
        }
    }
}
