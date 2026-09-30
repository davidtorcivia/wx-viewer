package zone.disinfo.wx.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.ConcurrentHashMap
import zone.disinfo.wx.R

private val fontInstances = ConcurrentHashMap<Pair<Int, Float>, FontFamily>()

/** Exact source variable font; width expresses hierarchy independently of weight. */
@OptIn(ExperimentalTextApi::class)
fun webFont(width: Float = 96f, weight: Int = 400): FontFamily =
    fontInstances.getOrPut(weight to width) {
        FontFamily(
            Font(
                R.font.anybody_variable,
                weight = FontWeight(weight),
                variationSettings =
                    FontVariation.Settings(
                        FontVariation.weight(weight),
                        FontVariation.width(width),
                    ),
            )
        )
    }

private val familyInstances = ConcurrentHashMap<Float, FontFamily>()

@OptIn(ExperimentalTextApi::class)
fun webFontFamily(width: Float = 96f): FontFamily =
    familyInstances.getOrPut(width) {
        FontFamily(
            (100..900 step 100).map { weight ->
                Font(
                    R.font.anybody_variable,
                    weight = FontWeight(weight),
                    variationSettings =
                        FontVariation.Settings(
                            FontVariation.weight(weight),
                            FontVariation.width(width),
                        ),
                )
            }
        )
    }

@Composable
fun WebText(
    text: String,
    size: Float = 14f,
    width: Float = 96f,
    weight: Int = 400,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    lineHeight: Float = size * 1.35f,
    maxLines: Int = Int.MAX_VALUE,
    letterSpacing: Float = 0f,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    Text(
        text,
        modifier,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        style =
            TextStyle(
                fontFamily = webFont(width, weight),
                fontWeight = FontWeight(weight),
                fontSynthesis = FontSynthesis.None,
                fontSize = size.sp,
                lineHeight = lineHeight.sp,
                letterSpacing = letterSpacing.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
    )
}

@Composable
fun SectionHeading(title: String, source: String = "") {
    androidx.compose.foundation.layout.Row(
        androidx.compose.ui.Modifier,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        WebText(title, 20f, 58f, 700)
        if (source.isNotBlank())
            WebText(source, 12f, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
