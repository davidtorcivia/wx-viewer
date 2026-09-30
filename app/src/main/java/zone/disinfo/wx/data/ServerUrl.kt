package zone.disinfo.wx.data

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** Rejects insecure or ambiguous addresses before any coordinate is transmitted. */
fun normalizeServerUrl(input: String): String {
    val uri =
        try {
            URI(input.trim())
        } catch (_: Exception) {
            throw IllegalArgumentException("Enter a valid HTTPS server address")
        }
    require(uri.scheme.equals("https", ignoreCase = true)) { "Server must use HTTPS" }
    require(!uri.host.isNullOrBlank()) { "Server address must include a hostname" }
    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
        "Server address cannot contain credentials, a query or a fragment"
    }
    require(uri.port == -1 || uri.port in 1..65535) { "Invalid server port" }
    val path = uri.path.orEmpty().trimEnd('/')
    require(path.split('/').none { it == "." || it == ".." } && !path.contains('\\')) {
        "Server path cannot contain relative segments"
    }
    val port = if (uri.port == 443) -1 else uri.port
    return URI("https", null, uri.host.lowercase(Locale.ROOT), port, path, null, null)
        .toASCIIString()
}

internal fun coordinates(lat: Double, lon: Double): String {
    require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) {
        "Invalid location coordinates"
    }
    fun part(n: Double): String =
        String.format(Locale.US, "%.4f", if (kotlin.math.abs(n) < 0.00005) 0.0 else n)
    return "lat=${part(lat)}&lon=${part(lon)}"
}

/** Includes the normalized endpoint and rounded coordinates, never a mutable place ID. */
internal fun forecastCacheKey(server: String, place: Place): String =
    stableHash(normalizeServerUrl(server) + "|" + coordinates(place.lat, place.lon))

internal fun stableHash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(
        ""
    ) {
        "%02x".format(it)
    }
