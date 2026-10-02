package zone.disinfo.wx.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.*
import zone.disinfo.wx.data.*

/** Source minute plate: it occupies no space when the server has no precipitation event. */
@Composable
fun LiveRainMinutes(
    nowcast: RainNowcast,
    zone: String,
    modifier: Modifier = Modifier,
    units: DisplayUnits = Units.IMPERIAL,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val now = nowMillis
    if (nowcast.rain == null || !nowcast.isFresh(now)) return
    val n = nowcast.dbz.size - 1
    val skip = max(0, ((now / 1000.0 - nowcast.timeMillis / 1000.0) / 60).roundToInt())
    val dark = MaterialTheme.colorScheme.surface.luminance() < .3f
    val ink = MaterialTheme.colorScheme.onSurface
    val kinds =
        (skip until min(nowcast.dbz.size, skip + n))
            .filter { i ->
                if (nowcast.hasTypedRates) (nowcast.rateMmH.getOrNull(i) ?: -1.0) >= WET_RATE_MMH
                else (nowcast.dbz[i] ?: -100.0) >= 20
            }
            .map { i ->
                if (nowcast.hasTypedRates) nowcast.kinds.getOrNull(i) ?: PrecipKind.UNKNOWN
                else if (nowcast.snow.getOrNull(i) == true) PrecipKind.SNOW else PrecipKind.RAIN
            }
            .distinct()
    // An event summary can outlive its last wet minute. Do not keep an empty activity plate.
    if (kinds.isEmpty()) return
    Column(
        modifier.testTag("live_precipitation").semantics {
            contentDescription = "Minute precipitation: " + kinds.joinToString { it.label }
        }
    ) {
        Canvas(Modifier.fillMaxWidth().height(44.dp)) {
            drawRect(ink.copy(alpha = .06f))
            val bar = size.width / n
            for (k in 0 until n) {
                val i = skip + k
                if (i >= nowcast.dbz.size) break
                val rate = nowcast.rateMmH.getOrNull(i)
                val wet =
                    if (nowcast.hasTypedRates) (rate ?: -1.0) >= WET_RATE_MMH
                    else (nowcast.dbz[i] ?: -100.0) >= 20
                if (!wet) continue
                val kind =
                    if (nowcast.hasTypedRates) nowcast.kinds.getOrNull(i) ?: PrecipKind.UNKNOWN
                    else if (nowcast.snow.getOrNull(i) == true) PrecipKind.SNOW else PrecipKind.RAIN
                val fraction =
                    if (nowcast.hasTypedRates)
                        .2 +
                            .8 *
                                max(
                                    0.0,
                                    ln(requireNotNull(rate) / WET_RATE_MMH) / ln(12 / WET_RATE_MMH),
                                )
                    else ((nowcast.dbz[i]!! - 10) / 40).coerceAtLeast(.2)
                val height = (min(1.0, fraction) * 100).roundToInt() / 100f * size.height
                drawRect(
                    precipitationColor(kind, dark),
                    Offset(k * bar, size.height - height),
                    Size(bar, height),
                )
            }
        }
        val every = if (n > 60) 30 else 15
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            (0 until n step every).forEach { k ->
                WebText(
                    if (k == 0) "now" else units.timeOf(now + k * 60_000L, zone),
                    11f,
                    weight = 500,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
            }
        }
    }
}
