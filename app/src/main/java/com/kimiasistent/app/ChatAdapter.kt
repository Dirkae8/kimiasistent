package com.kimiasistent.app

import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(private val onCopy: (String) -> Unit) :
    RecyclerView.Adapter<ChatAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val bubble: LinearLayout = v.findViewById(R.id.bubble)
        val img: ImageView = v.findViewById(R.id.image)
        val text: TextView = v.findViewById(R.id.text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_msg, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = ChatStore.messages.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val m = ChatStore.messages[pos]
        val dp = h.itemView.resources.displayMetrics.density
        val maxW = (h.itemView.resources.displayMetrics.widthPixels * 0.78f).toInt()

        if (m.image != null) {
            h.img.visibility = View.VISIBLE
            h.img.setImageBitmap(m.image)
            h.img.maxWidth = maxW
        } else {
            h.img.visibility = View.GONE
        }

        if (m.pending) {
            h.text.text = if (m.text.isEmpty()) "Кими печатает…" else m.text
            h.text.setTypeface(null, Typeface.ITALIC)
        } else {
            h.text.text = m.text
            h.text.setTypeface(null, Typeface.NORMAL)
        }
        h.text.maxWidth = maxW

        val lp = h.bubble.layoutParams as FrameLayout.LayoutParams
        lp.gravity = if (m.isUser) Gravity.END else Gravity.START
        h.bubble.layoutParams = lp
        h.bubble.setBackgroundResource(if (m.isUser) R.drawable.bg_user else R.drawable.bg_bot)

        h.bubble.setOnLongClickListener {
            onCopy(m.text)
            true
        }
    }
}
