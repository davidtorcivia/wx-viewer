package zone.disinfo.wx.ui

import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser

/**
 * Native rendering of overview.html's exact solid weather symbols, including their layered
 * transforms.
 */
@Composable
fun SourceWeatherGlyph(
    key: String,
    modifier: Modifier = Modifier,
    dark: Boolean = MaterialTheme.colorScheme.surface.luminance() < .3f,
) {
    val sun = (if (dark) webOklch(.82, .15, 75.0) else webOklch(.8, .16, 72.0)).toArgb()
    val moon = (if (dark) webOklch(.86, .06, 95.0) else webOklch(.8, .07, 95.0)).toArgb()
    val cloud = (if (dark) webOklch(.7, .012, 85.0) else webOklch(.8, .012, 85.0)).toArgb()
    val cloudBack = (if (dark) webOklch(.52, .01, 85.0) else webOklch(.88, .008, 85.0)).toArgb()
    val cloudWet = (if (dark) webOklch(.6, .025, 250.0) else webOklch(.66, .02, 250.0)).toArgb()
    val cloudStorm = (if (dark) webOklch(.42, .03, 270.0) else webOklch(.45, .03, 270.0)).toArgb()
    val rain = (if (dark) webOklch(.72, .13, 245.0) else webOklch(.58, .14, 248.0)).toArgb()
    val snow = (if (dark) webOklch(.82, .08, 230.0) else webOklch(.66, .11, 235.0)).toArgb()
    val bolt = (if (dark) webOklch(.85, .16, 90.0) else webOklch(.83, .17, 88.0)).toArgb()
    Canvas(modifier.size(24.dp)) {
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save()
        canvas.scale(size.width / 24f, size.height / 24f)
        fun shape(name: String, color: Int) =
            canvas.drawPath(weatherPaths.getValue(name), fill(color))
        fun rays() =
            canvas.drawPath(
                weatherPaths.getValue("rays"),
                stroke(sun, 2.1f).apply { strokeCap = Paint.Cap.ROUND },
            )
        fun sunshine() {
            rays()
            canvas.drawCircle(12f, 12f, 5.3f, fill(sun))
        }
        when (key) {
            "clear" -> sunshine()
            "clear-night" -> shape("moon", moon)
            "partly",
            "partly-night" -> {
                if (key == "partly") {
                    canvas.save()
                    canvas.translate(.1f, -.4f)
                    canvas.scale(.7f, .7f)
                    sunshine()
                    canvas.restore()
                } else shape("partly-moon", moon)
                canvas.save()
                canvas.translate(1.6f, .6f)
                canvas.scale(.93f, .93f)
                shape("cloud", cloud)
                canvas.restore()
            }
            "cloudy" -> {
                canvas.save()
                canvas.translate(5.2f, -4.2f)
                canvas.scale(.8f, .8f)
                shape("cloud", cloudBack)
                canvas.restore()
                canvas.save()
                canvas.translate(-1.2f, .3f)
                shape("cloud", cloud)
                canvas.restore()
            }
            "rain" -> {
                shape("cloud-up", cloudWet)
                shape("rain", rain)
            }
            "snow" -> {
                shape("cloud-up", cloudWet)
                canvas.drawPath(
                    weatherPaths.getValue("snow"),
                    stroke(snow, 1.5f).apply { strokeCap = Paint.Cap.ROUND },
                )
            }
            "storm" -> {
                shape("cloud-up", cloudStorm)
                shape("bolt", bolt)
            }
        }
        canvas.restore()
    }
}

private val weatherPaths: Map<String, Path> =
    mapOf(
            "cloud" to
                "M7 20h10.6a4.4 4.4 0 0 0 .5-8.77A6.3 6.3 0 0 0 6.1 10.4 4.8 4.8 0 0 0 7 20z",
            "cloud-up" to
                "M7 16.4h10.6a4.4 4.4 0 0 0 .5-8.77A6.3 6.3 0 0 0 6.1 6.8 4.8 4.8 0 0 0 7 16.4z",
            "rays" to
                "M12 1.6v2.3M12 20.1v2.3M1.6 12h2.3M20.1 12h2.3M4.65 4.65l1.6 1.6M17.75 17.75l1.6 1.6M4.65 19.35l1.6-1.6M17.75 6.25l1.6-1.6",
            "moon" to "M20.3 14.9A8.6 8.6 0 0 1 9.1 3.7a8.6 8.6 0 1 0 11.2 11.2z",
            "partly-moon" to "M13.6 9.6A6 6 0 0 1 5.8 1.8a6 6 0 1 0 7.8 7.8z",
            "rain" to
                "M8.2 17.6c.9 1.3 1.5 2.3 1.5 3.1a1.5 1.5 0 0 1-3 0c0-.8.6-1.8 1.5-3.1z M12.4 17.6c.9 1.3 1.5 2.3 1.5 3.1a1.5 1.5 0 0 1-3 0c0-.8.6-1.8 1.5-3.1z M16.6 17.6c.9 1.3 1.5 2.3 1.5 3.1a1.5 1.5 0 0 1-3 0c0-.8.6-1.8 1.5-3.1z",
            "snow" to
                "M8.2 17.6v4.2M6.4 18.65l3.6 2.1M6.4 20.75l3.6-2.1 M15.8 17.6v4.2M14 18.65l3.6 2.1M14 20.75l3.6-2.1",
            "bolt" to "M13.4 13.2l-4.3 5.6h3l-1.5 4.6 5-6.4h-3.1l1.9-3.8z",
        )
        .mapValues { (_, path) -> requireNotNull(PathParser.createPathFromPathData(path)) }
