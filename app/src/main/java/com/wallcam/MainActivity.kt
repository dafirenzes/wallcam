package com.wallcam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Region
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** 叠图层：按四个角点做透视变形，绘制半透明图片，并处理拖拽/双指缩放 */
class OverlayView(c: Context) : View(c) {
    var bmp: Bitmap? = null
    var locked = false
    var alphaF = 0.5f
    var onPinch: ((Float) -> Unit)? = null
    val pts = FloatArray(8)

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF2B51D.toInt(); style = Paint.Style.STROKE; strokeWidth = 3 * d
        pathEffect = DashPathEffect(floatArrayOf(20f, 14f), 0f)
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF2B51D.toInt(); style = Paint.Style.STROKE; strokeWidth = 3 * d
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2B51D.toInt() }
    private var active = -1
    private var dragAll = false
    private var ox = 0f
    private var oy = 0f
    private var base = FloatArray(8)
    private val sg = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(s: ScaleGestureDetector): Boolean {
            onPinch?.invoke(s.scaleFactor); return true
        }
    })

    fun resetQuad() {
        val w = width.toFloat(); val h = height.toFloat()
        if (w == 0f || h == 0f) return
        val bw = (bmp?.width ?: 4).toFloat(); val bh = (bmp?.height ?: 3).toFloat()
        val s = min(w * .7f / bw, h * .5f / bh)
        val dw = bw * s; val dh = bh * s
        val x = (w - dw) / 2; val y = (h - dh) / 2 - 40 * d
        floatArrayOf(x, y, x + dw, y, x + dw, y + dh, x, y + dh).copyInto(pts)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (pts.all { it == 0f }) resetQuad()
    }

    override fun onDraw(cv: Canvas) {
        bmp?.let { b ->
            val bw = b.width.toFloat(); val bh = b.height.toFloat()
            val n = 24
            val src = FloatArray((n + 1) * (n + 1) * 2)
            var k = 0
            for (j in 0..n) for (i in 0..n) { src[k++] = bw * i / n; src[k++] = bh * j / n }
            val m = Matrix()
            if (m.setPolyToPoly(floatArrayOf(0f, 0f, bw, 0f, bw, bh, 0f, bh), 0, pts, 0, 4)) {
                val dst = FloatArray(src.size)
                m.mapPoints(dst, src)
                paint.alpha = (alphaF * 255).toInt()
                cv.drawBitmapMesh(b, n, n, dst, 0, null, 0, paint)
            }
        }
        if (!locked) {
            cv.drawPath(quadPath(), line)
            for (i in 0..3) {
                cv.drawCircle(pts[2 * i], pts[2 * i + 1], 22 * d, ring)
                cv.drawCircle(pts[2 * i], pts[2 * i + 1], 4 * d, dot)
            }
        }
    }

    private fun quadPath() = Path().apply {
        moveTo(pts[0], pts[1])
        for (i in 1..3) lineTo(pts[2 * i], pts[2 * i + 1])
        close()
    }

    private fun inside(x: Float, y: Float): Boolean {
        val r = Region()
        r.setPath(quadPath(), Region(0, 0, width, height))
        return r.contains(x.toInt(), y.toInt())
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        sg.onTouchEvent(e)
        if (sg.isInProgress || e.pointerCount > 1) { active = -1; dragAll = false; return true }
        if (locked) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                var best = -1; var bd = 56 * d
                for (i in 0..3) {
                    val dd = hypot(e.x - pts[2 * i], e.y - pts[2 * i + 1])
                    if (dd < bd) { bd = dd; best = i }
                }
                active = best
                if (best >= 0) { ox = pts[2 * best] - e.x; oy = pts[2 * best + 1] - e.y }
                else if (inside(e.x, e.y)) { dragAll = true; ox = e.x; oy = e.y; base = pts.copyOf() }
            }
            MotionEvent.ACTION_MOVE -> {
                if (active >= 0) { pts[2 * active] = e.x + ox; pts[2 * active + 1] = e.y + oy }
                else if (dragAll) for (i in 0..3) {
                    pts[2 * i] = base[2 * i] + e.x - ox; pts[2 * i + 1] = base[2 * i + 1] + e.y - oy
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { active = -1; dragAll = false }
        }
        return true
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var ov: OverlayView
    private lateinit var zoomBar: SeekBar
    private var camera: Camera? = null
    private var provider: ProcessCameraProvider? = null
    private var back = true

    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else toast("需要相机权限才能使用")
    }
    private val pick = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { load(it) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
    private fun btn(t: String, f: (Button) -> Unit) = Button(this).apply {
        text = t; textSize = 13f; setOnClickListener { f(this) }
    }
    private fun SeekBar.onChange(f: (Int, Boolean) -> Unit) =
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) = f(p, u)
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    private fun row(label: String, bar: SeekBar) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply { text = label; setTextColor(Color.WHITE); setWidth(dp(56)) })
        addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preview = PreviewView(this)
        ov = OverlayView(this)
        zoomBar = SeekBar(this).apply { max = 100 }
        val opBar = SeekBar(this).apply { max = 100; progress = 50 }
        opBar.onChange { p, _ -> ov.alphaF = p / 100f; ov.invalidate() }
        zoomBar.onChange { p, user ->
            val z = camera?.cameraInfo?.zoomState?.value
            if (user && z != null) {
                val r = z.minZoomRatio * (z.maxZoomRatio / z.minZoomRatio).pow(p / 100f)
                camera?.cameraControl?.setZoomRatio(r)
            }
        }
        ov.onPinch = { f ->
            val z = camera?.cameraInfo?.zoomState?.value
            if (z != null && z.maxZoomRatio > z.minZoomRatio) {
                val r = (z.zoomRatio * f).coerceIn(z.minZoomRatio, z.maxZoomRatio)
                camera?.cameraControl?.setZoomRatio(r)
                zoomBar.progress = (100 * ln(r / z.minZoomRatio) / ln(z.maxZoomRatio / z.minZoomRatio)).toInt()
            }
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            addView(btn("选图") { pick.launch("image/*") }, lp())
            addView(btn("锁定四角") { b ->
                ov.locked = !ov.locked
                b.text = if (ov.locked) "解锁四角" else "锁定四角"
                ov.invalidate()
            }, lp())
            addView(btn("重置四角") { ov.resetQuad() }, lp())
            addView(btn("切换镜头") { back = !back; bind() }, lp())
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(204, 21, 19, 15))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(row("透明度", opBar))
            addView(row("变焦", zoomBar))
            addView(buttons)
        }
        val toggle = btn("≡") { panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }

        val root = FrameLayout(this)
        val full = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(preview, FrameLayout.LayoutParams(full, full))
        root.addView(ov, FrameLayout.LayoutParams(full, full))
        root.addView(panel, FrameLayout.LayoutParams(full, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        root.addView(toggle, FrameLayout.LayoutParams(dp(56), dp(48), Gravity.TOP or Gravity.END).apply {
            setMargins(dp(8), dp(8), dp(8), dp(8))
        })
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera() else perm.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({ provider = f.get(); bind() }, ContextCompat.getMainExecutor(this))
    }

    private fun bind() {
        val p = provider ?: return
        p.unbindAll()
        val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
        val sel = if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        try {
            camera = p.bindToLifecycle(this, sel, pv)
            zoomBar.progress = 0
        } catch (e: Exception) {
            toast("相机启动失败：" + e.message)
        }
    }

    private fun load(uri: Uri) {
        try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var s = 1
            while (max(o.outWidth, o.outHeight) / s > 2048) s *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = s }
            val b = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
            if (b == null) { toast("无法读取图片"); return }
            ov.bmp = b
            ov.resetQuad()
        } catch (e: Exception) {
            toast("无法读取图片")
        }
    }
}
