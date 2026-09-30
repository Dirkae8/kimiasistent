package com.kimiasistent.app.assist

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kimiasistent.app.ChatAdapter
import com.kimiasistent.app.ChatEngine
import com.kimiasistent.app.ChatMsg
import com.kimiasistent.app.ChatStore
import com.kimiasistent.app.MainActivity
import com.kimiasistent.app.R

/**
 * UI-панель ассистента (нижняя шторка с чатом, микрофоном и быстрыми действиями).
 * Используется и системной сессией (AssistSession), и запасной Activity.
 */
class AssistPanel(
    private val context: Context,
    root: View,
    private val onClose: () -> Unit,
    private val onRegion: (String) -> Unit,
    private val onNeedMicPermission: () -> Unit
) {
    private val ui = Handler(Looper.getMainLooper())
    private val adapter = ChatAdapter { t ->
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("kimi", t))
        Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
    }
    private val list = root.findViewById<RecyclerView>(R.id.assist_list)
    private val input = root.findViewById<EditText>(R.id.assist_input)
    private var voice: VoiceInput? = null

    init {
        list.layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
        list.adapter = adapter
        list.layoutParams.height =
            (context.resources.displayMetrics.heightPixels * 0.38f).toInt()

        root.findViewById<View>(R.id.assist_scrim).setOnClickListener { onClose() }
        root.findViewById<TextView>(R.id.assist_close).setOnClickListener { onClose() }
        root.findViewById<TextView>(R.id.assist_full).setOnClickListener {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            onClose()
        }
        root.findViewById<TextView>(R.id.assist_text_action).setOnClickListener { onRegion("ocr") }
        root.findViewById<TextView>(R.id.assist_area_action).setOnClickListener { onRegion("ask") }
        root.findViewById<ImageButton>(R.id.assist_mic).setOnClickListener { startListening() }
        root.findViewById<ImageButton>(R.id.assist_send).setOnClickListener { sendTyped() }
        refresh()
    }

    fun refresh() {
        adapter.notifyDataSetChanged()
        if (ChatStore.messages.isNotEmpty()) {
            list.smoothScrollToPosition(ChatStore.messages.size - 1)
        }
    }

    private fun sendTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        ChatStore.add(ChatMsg(true, text))
        refresh()
        send()
    }

    private fun send() {
        ChatEngine.send(context) { ui.post { refresh() } }
        refresh()
    }

    fun startListening() {
        if (!hasMicPermission()) {
            onNeedMicPermission()
            return
        }
        voice?.destroy()
        voice = VoiceInput(context, object : VoiceInput.Callback {
            override fun onVoiceReady() {
                Toast.makeText(context, "Говорите…", Toast.LENGTH_SHORT).show()
            }

            override fun onVoiceResult(text: String) {
                input.setText("")
                if (text.isNotBlank()) {
                    ChatStore.add(ChatMsg(true, text))
                    refresh()
                    send()
                }
            }

            override fun onVoiceError(code: Int) {
                if (code != -1 &&
                    code != SpeechRecognizer.ERROR_NO_MATCH &&
                    code != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    Toast.makeText(context, "Ошибка распознавания ($code)", Toast.LENGTH_SHORT).show()
                }
            }
        }).also { it.start() }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun destroy() {
        voice?.destroy()
    }
}
