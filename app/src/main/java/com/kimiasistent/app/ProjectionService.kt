package com.kimiasistent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager

class ProjectionService : Service() {

    companion object {
        const val EXTRA_RC = "rc"
        const val EXTRA_DATA = "data"
        private const val CH = "kimia_fg"

        @Volatile
        private var inst: ProjectionService? = null

        val isReady: Boolean
            get() = inst?.projection != null

        fun capture(cb: (Bitmap?) -> Unit) {
            val s = inst
            if (s == null || s.projection == null) {
                cb(null)
                return
            }
            s.captureInternal(cb)
        }
    }

    var projection: MediaProjection? = null
        private set

    private var vdisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var w = 0
    private var h = 0
    private var dpi = 0
    private var pending: ((Bitmap?) -> Unit)? = null
    private var last: Bitmap? = null
    private lateinit var handler: Handler
    private var thread: HandlerThread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || !intent.hasExtra(EXTRA_RC)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startFg()
        val rc = intent.getIntExtra(EXTRA_RC, -1)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) {
            inst = this
            return START_NOT_STICKY
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            mpm.getMediaProjection(rc, data)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                cleanup()
                stopSelf()
            }
        }, Handler(mainLooper))
        projection = mp
        setupReader(mp)
        inst = this
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH, "Запись экрана", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentTitle("Скриншоты по запросу")
            .setContentText("Кими может снимать экран, когда попросишь")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, n.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(2, n.build())
        }
    }

    private fun setupReader(mp: MediaProjection) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        w = metrics.widthPixels
        h = metrics.heightPixels
        dpi = metrics.densityDpi

        thread = HandlerThread("kimia_cap").also { it.start() }
        handler = Handler(thread!!.looper)

        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader!!.setOnImageAvailableListener({ r ->
            val img = try {
                r.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (img != null) {
                last = try {
                    toBitmap(img)
                } catch (e: Exception) {
                    null
                }
                img.close()
            }
            val p = pending
            if (p != null && last != null) {
                pending = null
                p(last)
            }
        }, handler)

        vdisplay = mp.createVirtualDisplay(
            "kimia", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, handler
        )
    }

    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * img.width
        val bmp = Bitmap.createBitmap(
            img.width + rowPadding / pixelStride,
            img.height,
            Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
    }

    @Synchronized
    fun captureInternal(cb: (Bitmap?) -> Unit) {
        pending = cb
        last = null
        // принудительно «толкаем» новый кадр
        try {
            vdisplay?.resize(w, h, dpi)
        } catch (_: Exception) {
        }
        handler.postDelayed({
            val p = pending
            if (p === cb) {
                pending = null
                cb(last)
            }
        }, 1200)
    }

    private fun cleanup() {
        try {
            vdisplay?.release()
        } catch (_: Exception) {
        }
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        vdisplay = null
        reader = null
        projection = null
        if (inst === this) inst = null
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
