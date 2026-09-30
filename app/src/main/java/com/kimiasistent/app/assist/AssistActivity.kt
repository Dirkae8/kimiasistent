package com.kimiasistent.app.assist

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.kimiasistent.app.ProjectionService
import com.kimiasistent.app.OverlayService
import com.kimiasistent.app.R

/**
 * Запасной вход в панель ассистента (если система запускает Activity вместо
 * VoiceInteractionSession), а также помощник для запросов разрешений
 * (микрофон / запись экрана) из сессии.
 */
class AssistActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REGION = "region_mode"
        const val EXTRA_MIC = "mic"
    }

    private var panel: AssistPanel? = null
    private var regionMode: String? = null
    private var micOnly = false

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val ok = res.resultCode == RESULT_OK && res.data != null
            if (ok && res.data != null) {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, ProjectionService::class.java)
                        .putExtra(ProjectionService.EXTRA_RC, res.resultCode)
                        .putExtra(ProjectionService.EXTRA_DATA, res.data)
                )
                val mode = regionMode ?: "ocr"
                Handler(Looper.getMainLooper()).postDelayed({
                    startService(
                        Intent(this, OverlayService::class.java)
                            .setAction(OverlayService.ACTION_REGION)
                            .putExtra(OverlayService.EXTRA_MODE, mode)
                    )
                    finish()
                }, 900)
            } else {
                Toast.makeText(this, "Нужно разрешение на запись экрана", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                if (micOnly) finish() else panel?.startListening()
            } else {
                Toast.makeText(this, "Нужен доступ к микрофону", Toast.LENGTH_SHORT).show()
                if (micOnly) finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        micOnly = intent.getBooleanExtra(EXTRA_MIC, false)
        regionMode = intent.getStringExtra(EXTRA_REGION)

        if (micOnly) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (regionMode != null) {
            runRegionFlow(regionMode!!)
            return
        }
        setContentView(R.layout.activity_assist)
        panel = AssistPanel(
            this,
            findViewById(R.id.assist_root),
            onClose = { finish() },
            onRegion = { runRegionFlow(it) },
            onNeedMicPermission = { micPermission.launch(Manifest.permission.RECORD_AUDIO) }
        )
    }

    override fun onResume() {
        super.onResume()
        panel?.refresh()
    }

    private fun runRegionFlow(mode: String) {
        if (ProjectionService.isReady) {
            startService(
                Intent(this, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_REGION)
                    .putExtra(OverlayService.EXTRA_MODE, mode)
            )
            finish()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } catch (e: Exception) {
            Toast.makeText(this, "Запись экрана недоступна", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() {
        panel?.destroy()
        super.onDestroy()
    }
}
