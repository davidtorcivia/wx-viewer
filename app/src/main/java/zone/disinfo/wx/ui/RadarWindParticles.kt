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
    private val particles = ArrayList<Particle>()
    private var screenPoints = DoubleArray(0)
    private var coordinates = DoubleArray(0)
    private val bandColors = IntArray(16)
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
        for (band in bandColors.indices) {
            val color = legend?.color((band + .5) * 5) ?: Color.GRAY
            fun tint(channel: Int) =
                if (dark) (channel + (255 - channel) * .4).roundToInt()
                else (channel * .7).roundToInt()
            bandColors[band] =
                Color.rgb(tint(Color.red(color)), tint(Color.green(color)), tint(Color.blue(color)))
        }
        paused = false
        last = 0
        trail?.eraseColor(Color.TRANSPARENT)
        if (grid == null) {
            particles.clear()
            trail?.recycle()
            trail = null
            trailCanvas = null
            invalidate()
        } else {
            ensureTrail()
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
        if (grid == null) return
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
        if (screenPoints.size != count * 2) {
            screenPoints = DoubleArray(count * 2)
            coordinates = DoubleArray(count * 2)
        }
    }

    private fun ensureTrail() {
        if (trail == null && grid != null && width > 0 && height > 0) {
            trail = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            trailCanvas = Canvas(requireNotNull(trail))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        trail?.recycle()
        trail = null
        trailCanvas = null
        ensureTrail()
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
        particles.forEachIndexed { i, p ->
            if (p.age++ > 100 || p.x < 0 || p.y < 0 || p.x > width || p.y > height) {
                p.x = Random.nextFloat() * width
                p.y = Random.nextFloat() * height
                p.age = 0
            }
            screenPoints[i * 2] = p.x.toDouble()
            screenPoints[i * 2 + 1] = p.y.toDouble()
        }
        // MapLibre returns [latitude, longitude] pairs. Project all particles in one
        // JNI call instead of allocating a PointF and LatLng for every particle.
        map.projection.fromScreenLocations(screenPoints, coordinates)
        particles.forEachIndexed { i, p ->
            val lat = coordinates[i * 2]
            val lon = coordinates[i * 2 + 1]
            val u = grid.value(lon, lat)
            val v = grid.value(lon, lat, 1)
            if (u == null || v == null) {
                p.age = 101
                return@forEachIndexed
            }
            val nx = p.x + (u / 2 * .25 * density * dt).toFloat()
            val ny = p.y - (v / 2 * .25 * density * dt).toFloat()
            val mph = hypot(u, v) / 2 * 2.23694
            val band = (mph / 5).toInt().coerceIn(0, 15)
            paint.color = bandColors[band]
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
