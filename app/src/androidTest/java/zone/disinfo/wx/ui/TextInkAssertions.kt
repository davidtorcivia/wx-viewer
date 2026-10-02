package zone.disinfo.wx.ui

import androidx.compose.ui.text.TextLayoutResult
import org.json.JSONArray
import org.json.JSONObject

// Compose 1.8.3's String-text semantics pairs the measured layout size with a
// paragraph reconstructed at the incoming maxWidth. hasVisualOverflow therefore
// mistakes unused allocated width for clipping. Check the actual line extents and
// completeness instead, retaining height, truncation, and horizontal ink checks.
internal fun TextLayoutResult.hasClippedTextLines(): Boolean =
    lineCount == 0 || multiParagraph.didExceedMaxLines ||
        getLineEnd(lineCount - 1, visibleEnd = false) < layoutInput.text.length ||
        (0 until lineCount).any { line ->
            isLineEllipsized(line) || getLineLeft(line) < -1f ||
                getLineRight(line) > size.width + 1f || getLineTop(line) < -1f ||
                getLineBottom(line) > size.height + 1f
        }

internal fun TextLayoutResult.inkEvidence(): JSONObject = JSONObject()
    .put("text", layoutInput.text.text).put("width", size.width).put("height", size.height)
    .put("paragraphWidth", multiParagraph.width).put("paragraphHeight", multiParagraph.height)
    .put("constraints", layoutInput.constraints.toString())
    .put("reportedOverflowWidth", didOverflowWidth).put("reportedOverflowHeight", didOverflowHeight)
    .put("clippedTextLines", hasClippedTextLines())
    .put("lines", JSONArray((0 until lineCount).map { line ->
        JSONObject().put("left", getLineLeft(line)).put("right", getLineRight(line))
            .put("top", getLineTop(line)).put("bottom", getLineBottom(line))
            .put("end", getLineEnd(line, visibleEnd = false)).put("ellipsized", isLineEllipsized(line))
    }))
