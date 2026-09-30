package com.kimiasistent.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

object KimiClient {

    data class Cfg(val key: String, val base: String, val model: String, val ocrModel: String)

    const val DEF_BASE = "https://api.moonshot.ai/v1"
    const val DEF_MODEL = "moonshot-v1-8k-vision-preview"

    fun loadCfg(ctx: Context): Cfg {
        val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val model = p.getString("model", DEF_MODEL)?.takeIf { it.isNotBlank() } ?: DEF_MODEL
        return Cfg(
            key = p.getString("api_key", "") ?: "",
            base = (p.getString("base_url", DEF_BASE) ?: DEF_BASE).trim().trimEnd('/').ifEmpty { DEF_BASE },
            model = model,
            ocrModel = model
        )
    }

    fun saveCfg(ctx: Context, c: Cfg) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("api_key", c.key)
            .putString("base_url", c.base)
            .putString("model", c.model)
            .apply()
    }

    fun hasKey(ctx: Context): Boolean = loadCfg(ctx).key.isNotBlank()

    class ApiMsg(val role: String, val text: String, val image: Bitmap? = null)

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .build()

    fun chat(ctx: Context, history: List<ApiMsg>, onDone: (ok: Boolean, result: String) -> Unit) {
        val cfg = loadCfg(ctx)
        if (cfg.key.isBlank()) {
            onDone(false, "Нет API-ключа. Нажми ⚙ в правом верхнем углу и вставь ключ Kimi с platform.moonshot.ai")
            return
        }
        val arr = JSONArray()
        val sys = JSONObject()
        sys.put("role", "system")
        sys.put(
            "content",
            "Ты — Кими, дружелюбный и умный ассистент внутри Android-приложения. " +
                "Отвечай на языке пользователя (обычно на русском), кратко, по делу и понятно."
        )
        arr.put(sys)
        for (m in history) {
            val o = JSONObject()
            o.put("role", m.role)
            if (m.image != null) {
                val content = JSONArray()
                val t = JSONObject()
                t.put("type", "text")
                t.put("text", m.text.ifBlank { "Что на этом изображении?" })
                val img = JSONObject()
                img.put("type", "image_url")
                val iu = JSONObject()
                iu.put("url", "data:image/jpeg;base64," + toB64(m.image))
                img.put("image_url", iu)
                content.put(t)
                content.put(img)
                o.put("content", content)
            } else {
                o.put("content", m.text)
            }
            arr.put(o)
        }
        val body = JSONObject()
        body.put("model", cfg.model)
        body.put("messages", arr)
        body.put("temperature", 0.6)
        post(cfg, body, onDone)
    }

    fun ocr(ctx: Context, bmp: Bitmap, onDone: (ok: Boolean, result: String) -> Unit) {
        val cfg = loadCfg(ctx)
        if (cfg.key.isBlank()) {
            onDone(false, "Нет API-ключа. Нажми ⚙ и вставь ключ Kimi")
            return
        }
        val arr = JSONArray()
        val sys = JSONObject()
        sys.put("role", "system")
        sys.put("content", "Ты — OCR-движок. Возвращай ТОЛЬКО текст, найденный на изображении, без комментариев и пояснений. Если текста нет — верни пустую строку.")
        arr.put(sys)
        val o = JSONObject()
        o.put("role", "user")
        val content = JSONArray()
        val t = JSONObject()
        t.put("type", "text")
        t.put("text", "Извлеки весь текст с изображения. Сохрани оригинальный язык, структуру и переносы строк. Верни только извлечённый текст.")
        val img = JSONObject()
        img.put("type", "image_url")
        val iu = JSONObject()
        iu.put("url", "data:image/jpeg;base64," + toB64(bmp))
        img.put("image_url", iu)
        content.put(t)
        content.put(img)
        o.put("content", content)
        arr.put(o)
        val body = JSONObject()
        body.put("model", cfg.ocrModel)
        body.put("messages", arr)
        body.put("temperature", 0.1)
        post(cfg, body, onDone)
    }

    private fun toB64(src: Bitmap): String {
        val max = 1280
        var b = src
        val m = maxOf(b.width, b.height)
        if (m > max) {
            val s = max.toFloat() / m
            b = Bitmap.createScaledBitmap(
                b,
                (b.width * s).toInt().coerceAtLeast(1),
                (b.height * s).toInt().coerceAtLeast(1),
                true
            )
        }
        val os = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 82, os)
        return Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP)
    }

    private fun post(cfg: Cfg, body: JSONObject, onDone: (Boolean, String) -> Unit) {
        val req = Request.Builder()
            .url(cfg.base + "/chat/completions")
            .addHeader("Authorization", "Bearer " + cfg.key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onDone(false, "Сеть недоступна: ${e.message ?: "ошибка соединения"}")
            }

            override fun onResponse(call: Call, resp: Response) {
                resp.use { r ->
                    val txt = r.body?.string() ?: ""
                    try {
                        val j = JSONObject(txt)
                        if (!r.isSuccessful) {
                            val msg = j.optJSONObject("error")?.optString("message")
                                ?.takeIf { it.isNotBlank() } ?: txt.take(300)
                            onDone(false, "Ошибка API (${r.code}): $msg")
                            return
                        }
                        val content = j.getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").optString("content")
                        onDone(true, content.ifBlank { "(пустой ответ)" })
                    } catch (e: Exception) {
                        onDone(false, "Не удалось разобрать ответ сервера: ${txt.take(200)}")
                    }
                }
            }
        })
    }
}
