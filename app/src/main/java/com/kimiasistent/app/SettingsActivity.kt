package com.kimiasistent.app

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
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
    }
}
