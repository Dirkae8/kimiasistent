package com.kimiasistent.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kimiasistent.app.assist.VoiceInput

class MainActivity : AppCompatActivity() {

    companion object {
        var pendingAutoImage: Bitmap? = null
    }

    private lateinit var adapter: ChatAdapter
    private lateinit var list: RecyclerView
    private lateinit var input: EditText
    private lateinit var previewBar: LinearLayout
    private lateinit var previewImg: ImageView

    private var pendingImage: Bitmap? = null
    private var afterProjection: ((Boolean) -> Unit)? = null
    private var voice: VoiceInput? = null
    private val ui = Handler(Looper.getMainLooper())

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val ok = res.resultCode == RESULT_OK && res.data != null
            val cb = afterProjection
            afterProjection = null
            if (ok && res.data != null) {
                val i = Intent(this, ProjectionService::class.java)
                    .putExtra(ProjectionService.EXTRA_RC, res.resultCode)
                    .putExtra(ProjectionService.EXTRA_DATA, res.data)
                ContextCompat.startForegroundService(this, i)
                ui.postDelayed({ cb?.invoke(true) }, 1000)
            } else {
                Toast.makeText(this, "Нужно разрешение на запись экрана", Toast.LENGTH_SHORT).show()
                cb?.invoke(false)
            }
        }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted && !micAskedBefore()) {
                rememberMicAsked()
                Toast.makeText(this, "Микрофон нужен для голосового ввода — включить можно в настройках", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ChatStore.load(this)

        list = findViewById(R.id.list)
        input = findViewById(R.id.input)
        previewBar = findViewById(R.id.previewBar)
        previewImg = findViewById(R.id.previewImg)

        adapter = ChatAdapter { text ->
            copyText(text)
            Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
        }
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter

        findViewById<ImageButton>(R.id.sendBtn).setOnClickListener { sendCurrent() }
        findViewById<ImageButton>(R.id.micBtn).setOnClickListener { onMic() }
        findViewById<ImageButton>(R.id.shotBtn).setOnClickListener { onScreenshot() }
        findViewById<TextView>(R.id.settingsBtn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.bubbleToggle).setOnClickListener { onBubbleToggle() }
        findViewById<TextView>(R.id.clearChat).setOnClickListener {
            ChatStore.clear()
            ChatStore.save(this)
            adapter.notifyDataSetChanged()
        }
        findViewById<TextView>(R.id.previewCancel).setOnClickListener {
            pendingImage = null
            previewBar.visibility = View.GONE
        }

        if (!KimiClient.hasKey(this)) {
            Toast.makeText(this, "Вставь API-ключ в настройках ⚙ (есть бесплатные варианты)", Toast.LENGTH_LONG).show()
        }
        // Микрофон понадобится и в чате, и в системной панели ассистента — просим сразу
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged()
        val img = pendingAutoImage
        if (img != null) {
            pendingAutoImage = null
            ChatStore.add(ChatMsg(true, "Что на этом изображении?", img))
            refresh()
            sendToApi()
        }
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.getStringExtra("action")) {
            "voice" -> ui.post { onMic() }
            "region_ocr" -> requestProjectionThen("ocr")
            "region_ask" -> requestProjectionThen("ask")
        }
    }

    private fun requestProjectionThen(mode: String) {
        requestProjection { ok ->
            if (ok) {
                val i = Intent(this, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_REGION)
                    .putExtra(OverlayService.EXTRA_MODE, mode)
                startService(i)
            }
        }
    }

    private fun requestProjection(cb: (Boolean) -> Unit) {
        if (ProjectionService.isReady) {
            cb(true)
            return
        }
        afterProjection = cb
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } catch (e: Exception) {
            afterProjection = null
            Toast.makeText(this, "Запись экрана недоступна", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onScreenshot() {
        requestProjection { ok ->
            if (!ok) return@requestProjection
            ProjectionService.capture { bmp ->
                ui.post {
                    if (bmp == null) {
                        Toast.makeText(this, "Не удалось сделать скриншот", Toast.LENGTH_SHORT).show()
                    } else {
                        pendingImage = bmp
                        previewImg.setImageBitmap(bmp)
                        previewBar.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun onMic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startVoice()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoice() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Голосовой ввод недоступен на этом устройстве", Toast.LENGTH_SHORT).show()
            return
        }
        voice?.destroy()
        voice = VoiceInput(this, object : VoiceInput.Callback {
            override fun onVoiceReady() {
                Toast.makeText(this@MainActivity, "Говорите…", Toast.LENGTH_SHORT).show()
            }

            override fun onVoiceResult(text: String) {
                input.append(text)
                input.requestFocus()
            }

            override fun onVoiceError(code: Int) {
                if (code != -1 &&
                    code != SpeechRecognizer.ERROR_NO_MATCH &&
                    code != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    Toast.makeText(this@MainActivity, "Ошибка распознавания ($code)", Toast.LENGTH_SHORT).show()
                }
            }
        }).also { it.start() }
    }

    private fun onBubbleToggle() {
        if (Settings.canDrawOverlays(this)) {
            if (OverlayService.running) {
                stopService(Intent(this, OverlayService::class.java))
                Toast.makeText(this, "Плашка выключена", Toast.LENGTH_SHORT).show()
            } else {
                ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
                Toast.makeText(this, "Плашка включена — ищи кружок на экране", Toast.LENGTH_SHORT).show()
            }
        } else {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
            Toast.makeText(this, "Разреши «поверх других окон» для Кими", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendCurrent() {
        val text = input.text.toString().trim()
        if (text.isEmpty() && pendingImage == null) return
        val img = pendingImage
        pendingImage = null
        previewBar.visibility = View.GONE
        input.setText("")
        ChatStore.add(ChatMsg(true, text.ifEmpty { "Что на этом изображении?" }, img))
        refresh()
        sendToApi()
    }

    private fun refresh() {
        adapter.notifyDataSetChanged()
        if (ChatStore.messages.isNotEmpty()) {
            list.smoothScrollToPosition(ChatStore.messages.size - 1)
        }
    }

    private fun sendToApi() {
        ChatEngine.send(this) { ui.post { refresh() } }
        refresh()
    }

    private fun copyText(t: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("kimi", t))
    }

    private fun micAskedBefore(): Boolean =
        getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("mic_asked", false)

    private fun rememberMicAsked() {
        getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("mic_asked", true).apply()
    }

    override fun onDestroy() {
        voice?.destroy()
        super.onDestroy()
    }
}
