package zone.disinfo.wx.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*
import kotlin.random.Random
import org.maplibre.android.maps.MapLibreMap

/** Field-grid wind particles, source speed/color rules in native map pixels. */
internal class RadarWindParticles(context: Context) : View(context) {
    private data class Particle(var x: Float, var y: Float, var age: Int)

    private var grid: RadarGrid? = null
    private var map: MapLibreMap? = null
    private var legend: RadarFieldLegend? = null
    private var dark = false
    private val particles = ArrayList<Particle>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private var trail: Bitmap? = null
    private var trailCanvas: Canvas? = null
    private var last = 0L
    private var paused = false

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setGrid(grid: RadarGrid?, map: MapLibreMap?, legend: RadarFieldLegend?, dark: Boolean) {
        this.grid = grid
        this.map = map
        this.legend = legend
        this.dark = dark
        paused = false
        trail?.eraseColor(Color.TRANSPARENT)
        if (grid == null) {
            particles.clear()
            invalidate()
        } else {
            resetParticles()
            postInvalidateOnAnimation()
        }
    }

    fun pause() {
        paused = true
        trail?.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    private fun resetParticles() {
        particles.clear()
        val density = resources.displayMetrics.density
        val count = (width * height / (density * density * 900)).toInt().coerceIn(0, 1400)
        repeat(count) {
            particles.add(
                Particle(
                    Random.nextFloat() * width,
                    Random.nextFloat() * height,
                    Random.nextInt(100),
                )
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        trail?.recycle()
        trail = if (w > 0 && h > 0) Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) else null
        trailCanvas = trail?.let { Canvas(it) }
        resetParticles()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val grid = grid ?: return
        val map = map ?: return
        val target = trailCanvas ?: return
        if (paused || !isShown || !ValueAnimator.areAnimatorsEnabled()) return
        val now = System.nanoTime()
        val dt = if (last == 0L) 1f else ((now - last) / 16_666_666.0).toFloat().coerceIn(.1f, 3f)
        last = now
        target.drawColor(
            Color.argb((255 * .93.pow(dt.toDouble())).toInt(), 255, 255, 255),
            PorterDuff.Mode.DST_IN,
        )
        val density = resources.displayMetrics.density
        particles.forEach { p ->
            if (p.age++ > 100 || p.x < 0 || p.y < 0 || p.x > width || p.y > height) {
                p.x = Random.nextFloat() * width
                p.y = Random.nextFloat() * height
                p.age = 0
            }
            val ll = map.projection.fromScreenLocation(PointF(p.x, p.y))
            val u = grid.value(ll.longitude, ll.latitude)
            val v = grid.value(ll.longitude, ll.latitude, 1)
            if (u == null || v == null) {
                p.age = 101
                return@forEach
            }
            val nx = p.x + (u / 2 * .25 * density * dt).toFloat()
            val ny = p.y - (v / 2 * .25 * density * dt).toFloat()
            val mph = hypot(u, v) / 2 * 2.23694
            val band = (mph / 5).toInt().coerceIn(0, 15)
            val color = legend?.color((band + .5) * 5) ?: Color.GRAY
            fun tint(channel: Int) =
                if (dark) (channel + (255 - channel) * .4).roundToInt()
                else (channel * .7).roundToInt()
            paint.color =
                Color.rgb(tint(Color.red(color)), tint(Color.green(color)), tint(Color.blue(color)))
            paint.strokeWidth = (.7f + band * .18f) * density
            target.drawLine(p.x, p.y, nx, ny, paint)
            p.x = nx
            p.y = ny
        }
        trail?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        last = 0
        super.onDetachedFromWindow()
    }
}
