package zone.disinfo.wx.ui

/** A measured control rectangle in native map screen pixels, not a whole top/bottom band. */
internal data class RadarLabelRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Include every variable anchor, the radial offset, and glyph halo without hiding the field. */
internal fun radarNumberOverlapsControls(
    x: Float, y: Float, label: String, rank: Int, density: Float, controls: List<RadarLabelRect>,
): Boolean {
    val text = (if (rank < 3) 13f else 11f) * density
    // Numeric weather labels use compact digits/units. One em per character also
    // bounds wider fallback fonts; a left/right anchor can extend the full width.
    val horizontal = text * (.95f + label.length.coerceAtLeast(1)) + 2f * density
    val vertical = text * 1.95f + 2f * density
    return controls.any {
        x + horizontal > it.left && x - horizontal < it.right &&
            y + vertical > it.top && y - vertical < it.bottom
    }
}

internal data class RadarNumberLabel(val longitude: Double, val latitude: Double, val rank: Int, val text: String)
