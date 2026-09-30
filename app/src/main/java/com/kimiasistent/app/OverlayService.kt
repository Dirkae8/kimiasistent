package com.kimiasistent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "stop"
        const val ACTION_REGION = "region"
        const val EXTRA_MODE = "mode"

        @Volatile
        var running = false
            private set
    }

    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private var bubble: FrameLayout? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var menuRoot: FrameLayout? = null
    private var regionRoot: FrameLayout? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (intent?.action == ACTION_STOP) {
            removeAll()
            stopSelf()
            return START_NOT_STICKY
        }
        startFg()
        if (intent?.action == ACTION_REGION) {
            val mode = intent.getStringExtra(EXTRA_MODE) ?: "ocr"
            ui.post { showRegion(mode) }
        } else {
            ui.post { showBubble() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        removeAll()
        super.onDestroy()
    }

    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("kimia_overlay", "Плавающий помощник", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "kimia_overlay")
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentTitle("Кими рядом")
            .setContentText("Кружок поверх экрана: выделить текст · спросить про область")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n.build())
        }
    }

    private fun overlayParams(width: Int, height: Int, focusable: Boolean): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (focusable) {
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        }
        return p
    }

    // ---------- Пузырь ----------

    private fun showBubble() {
        if (bubble != null) return
        val dp = resources.displayMetrics.density
        val b = FrameLayout(this)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(0xE6171721.toInt())
        bg.setStroke((2 * dp).toInt(), 0xFF7C6CFF.toInt())
        b.background = bg

        val tv = TextView(this)
        tv.text = "К"
        tv.setTextColor(0xFFFFFFFF.toInt())
        tv.textSize = 22f
        tv.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        tv.gravity = Gravity.CENTER
        b.addView(
            tv,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        val lp = overlayParams((54 * dp).toInt(), (54 * dp).toInt(), false)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = (24 * dp).toInt()
        lp.y = (180 * dp).toInt()

        var downX = 0f
        var downY = 0f
        var px = 0
        var py = 0
        var moved = false
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    px = lp.x
                    py = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (moved || dx * dx + dy * dy > 100) {
                        moved = true
                        lp.x = px + dx
                        lp.y = py + dy
                        try {
                            wm.updateViewLayout(b, lp)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) showMenu()
                    true
                }
                else -> false
            }
        }

        bubble = b
        bubbleLp = lp
        try {
            wm.addView(b, lp)
        } catch (_: Exception) {
            bubble = null
        }
    }

    private fun removeBubble() {
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
        bubbleLp = null
    }

    private fun pill(text: String, bgColor: Int, onClick: () -> Unit): TextView {
        val dp = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = 22f * dp
                setColor(bgColor)
            }
            val ph = (14 * dp).toInt()
            setPadding(ph, (10 * dp).toInt(), ph, (10 * dp).toInt())
            setOnClickListener { onClick() }
        }
    }

    // ---------- Меню ----------

    private fun showMenu() {
        if (menuRoot != null) return
        val dp = resources.displayMetrics.density

        val root = FrameLayout(this)
        root.setBackgroundColor(0x99000000.toInt())
        root.setOnClickListener { hideMenu() }

        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        val pbg = GradientDrawable()
        pbg.cornerRadius = 22f * dp
        pbg.setColor(0xF0161620.toInt())
        pbg.setStroke((1 * dp).toInt(), 0xFF2C2C3A.toInt())
        panel.background = pbg
        panel.setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())

        fun item(label: String, action: () -> Unit) {
            val t = TextView(this)
            t.text = label
            t.setTextColor(0xFFECECF1.toInt())
            t.textSize = 16f
            t.setPadding((14 * dp).toInt(), (13 * dp).toInt(), (14 * dp).toInt(), (13 * dp).toInt())
            t.setOnClickListener {
                hideMenu()
                action()
            }
            panel.addView(t)
        }

        item("💬  Открыть чат") { openChat(null) }
        item("📋  Скопировать текст с экрана") { showRegion("ocr") }
        item("🖼  Спросить про область экрана") { showRegion("ask") }
        item("🎤  Голосовой ввод") { openChat("voice") }

        val gl = FrameLayout.LayoutParams((290 * dp).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT)
        gl.gravity = Gravity.CENTER
        root.addView(panel, gl)

        menuRoot = root
        try {
            wm.addView(root, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, false))
        } catch (_: Exception) {
            menuRoot = null
        }
    }

    private fun hideMenu() {
        menuRoot?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        menuRoot = null
    }

    private fun openChat(action: String?) {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (action != null) i.putExtra("action", action)
        startActivity(i)
    }

    // ---------- Выделение области ----------

    private fun showRegion(mode: String) {
        if (!ProjectionService.isReady) {
            openChat(if (mode == "ask") "region_ask" else "region_ocr")
            return
        }
        hideMenu()
        if (regionRoot != null) return
        val dp = resources.displayMetrics.density

        val root = FrameLayout(this)
        val region = RegionView(this)
        root.addView(
            region,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER
        val bl = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        bl.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        bl.bottomMargin = (28 * dp).toInt()
        root.addView(bar, bl)

        fun addToBar(t: TextView) {
            val l = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            l.marginEnd = (10 * dp).toInt()
            bar.addView(t, l)
        }
        val bCopy = pill("📋 Текст", 0xFF7C6CFF.toInt()) { takeShot(region.selRect, "ocr") }
        val bAsk = pill("🖼 Спросить", 0xFF7C6CFF.toInt()) { takeShot(region.selRect, "ask") }
        val bX = pill("✕", 0xFF3A3A4A.toInt()) { finishRegion() }
        addToBar(bCopy)
        addToBar(bAsk)
        addToBar(bX)
        bar.visibility = View.GONE

        region.onSelected = { bar.visibility = View.VISIBLE }
        region.onCanceled = { finishRegion() }

        regionRoot = root
        try {
            wm.addView(root, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, true))
        } catch (_: Exception) {
            regionRoot = null
        }
    }

    private fun finishRegion() {
        regionRoot?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        regionRoot = null
    }

    private fun takeShot(rect: Rect, mode: String) {
        val sel = Rect(rect)
        finishRegion()
        removeBubble()
        ui.postDelayed({
            ProjectionService.capture { bmp ->
                showBubble()
                if (bmp == null) {
                    Toast.makeText(this, "Не удалось сделать снимок экрана", Toast.LENGTH_SHORT).show()
                    return@capture
                }
                val cropped = cropToRect(bmp, sel)
                if (mode == "ocr") {
                    KimiClient.ocr(this, cropped) { ok, text ->
                        ui.post {
                            if (ok && text.isNotBlank()) {
                                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("kimi", text))
                                Toast.makeText(this, "Текст скопирован ✓", Toast.LENGTH_LONG).show()
                                ChatStore.add(ChatMsg(false, "📋 Скопированный текст:\n\n$text"))
                                ChatStore.save(this)
                            } else if (ok) {
                                Toast.makeText(this, "Текст на этой области не найден", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, text, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } else {
                    MainActivity.pendingAutoImage = cropped
                    openChat(null)
                }
            }
        }, 350)
    }

    private fun cropToRect(bmp: Bitmap, r: Rect): Bitmap {
        val l = r.left.coerceIn(0, bmp.width - 1)
        val t = r.top.coerceIn(0, bmp.height - 1)
        val wd = r.width().coerceIn(1, bmp.width - l)
        val ht = r.height().coerceIn(1, bmp.height - t)
        return Bitmap.createBitmap(bmp, l, t, wd, ht)
    }

    private fun removeAll() {
        hideMenu()
        finishRegion()
        removeBubble()
    }

    // ---------- View для выделения ----------

    inner class RegionView(context: Context) : View(context) {
        var selRect: Rect = Rect()
        var onSelected: (() -> Unit)? = null
        var onCanceled: (() -> Unit)? = null

        private var startX = 0f
        private var startY = 0f
        private val dim = Paint().apply { color = 0x99000000.toInt() }
        private val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        private val stroke = Paint().apply {
            style = Paint.Style.STROKE
            color = 0xFF7C6CFF.toInt()
            strokeWidth = 3f * resources.displayMetrics.density
        }
        private val hint = Paint().apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 16f * resources.displayMetrics.density
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            if (selRect.isEmpty) {
                c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
                c.drawText("Выдели пальцем область экрана", width / 2f, height / 2f, hint)
            } else {
                val sc = c.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
                c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
                c.drawRect(RectF(selRect), clear)
                c.restoreToCount(sc)
                c.drawRect(RectF(selRect), stroke)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX
                    startY = e.rawY
                    selRect.set(startX.toInt(), startY.toInt(), startX.toInt(), startY.toInt())
                }
                MotionEvent.ACTION_MOVE -> {
                    selRect.set(
                        min(startX, e.rawX).toInt(),
                        min(startY, e.rawY).toInt(),
                        max(startX, e.rawX).toInt(),
                        max(startY, e.rawY).toInt()
                    )
                }
                MotionEvent.ACTION_UP -> {
                    if (selRect.width() < 30 || selRect.height() < 30) {
                        selRect.setEmpty()
                        onCanceled?.invoke()
                    } else {
                        onSelected?.invoke()
                    }
                }
            }
            invalidate()
            return true
        }
    }
}
