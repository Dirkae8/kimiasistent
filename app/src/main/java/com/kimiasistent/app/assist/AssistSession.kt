package com.kimiasistent.app.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.LayoutInflater
import android.view.View
import com.kimiasistent.app.R

/**
 * Панель ассистента, открывающаяся системным жестом (долгое нажатие «Домой»,
 * свайп из угла экрана, удержание кнопки питания) — как Google Ассистент.
 * Работает поверх любого приложения, включая чужие.
 */
class AssistSession(context: Context) : VoiceInteractionSession(context) {

    private var panel: AssistPanel? = null

    override fun onCreateContentView(): View? {
        val v = LayoutInflater.from(context).inflate(R.layout.activity_assist, null)
        panel = AssistPanel(
            context,
            v,
            onClose = { hide() },
            onRegion = { mode ->
                context.startActivity(
                    Intent(context, AssistActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(AssistActivity.EXTRA_REGION, mode)
                )
                hide()
            },
            onNeedMicPermission = {
                context.startActivity(
                    Intent(context, AssistActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(AssistActivity.EXTRA_MIC, true)
                )
            }
        )
        return v
    }

    override fun onShow(args: Bundle?) {
        panel?.refresh()
        panel?.startListening()
    }

    override fun onHide() {
        panel?.destroy()
        super.onHide()
    }
}
