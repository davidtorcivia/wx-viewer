package zone.disinfo.wx.ui

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.SymbolLayer

/** Basemap roads may occur after its first symbol layer; weather values belong above all of them. */
internal fun addRadarNumbersLayer(style: Style, layer: SymbolLayer) {
    style.addLayer(layer)
}
