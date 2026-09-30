package com.kimiasistent.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val key = findViewById<EditText>(R.id.etKey)
        val base = findViewById<EditText>(R.id.etBase)
        val model = findViewById<EditText>(R.id.etModel)

        val c = KimiClient.loadCfg(this)
        key.setText(c.key)
        base.setText(c.base)
        model.setText(c.model)

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val m = model.text.toString().trim().ifEmpty { KimiClient.DEF_MODEL }
            KimiClient.saveCfg(
                this,
                KimiClient.Cfg(
                    key = key.text.toString().trim(),
                    base = base.text.toString().trim().ifEmpty { KimiClient.DEF_BASE },
                    model = m,
                    ocrModel = m
                )
            )
            Toast.makeText(this, "Сохранено ✓", Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            ChatStore.clear()
            ChatStore.save(this)
            Toast.makeText(this, "История чата очищена", Toast.LENGTH_SHORT).show()
        }

        // Пресеты провайдеров: приложение совместимо с любым OpenAI-совместимым API
        findViewById<Button>(R.id.btnKimi).setOnClickListener {
            base.setText(KimiClient.DEF_BASE)
            model.setText(KimiClient.DEF_MODEL)
            Toast.makeText(this, "Вставь ключ с platform.moonshot.ai (новым аккаунтам дают стартовые кредиты)", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btnGemini).setOnClickListener {
            base.setText("https://generativelanguage.googleapis.com/v1beta/openai")
            model.setText("gemini-2.5-flash")
            Toast.makeText(this, "Бесплатный ключ: aistudio.google.com/apikey — без карты, без срока", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btnOpenRouter).setOnClickListener {
            base.setText("https://openrouter.ai/api/v1")
            model.setText("google/gemini-2.0-flash-exp:free")
            Toast.makeText(this, "Бесплатный ключ: openrouter.ai — модели с пометкой :free без оплаты", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btnFreeKey).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
            } catch (e: Exception) {
            }
        }

        // Запуск как системный ассистент (жест долгого нажатия «Домой»)
        findViewById<Button>(R.id.btnAssist).setOnClickListener {
            openAssistantSettings()
        }
    }

    private fun openAssistantSettings() {
        val intents = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in intents) {
            try {
                startActivity(i)
                break
            } catch (e: Exception) {
            }
        }
        Toast.makeText(
            this,
            "Выбери «Кими Ассистент» как цифровой помощник — жест «Домой» будет открывать его",
            Toast.LENGTH_LONG
        ).show()
    }
}
