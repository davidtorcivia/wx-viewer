package zone.disinfo.wx.data

/** Published temperature ensemble values, in Fahrenheit. Null values remain gaps. */
data class ChartEnsemblePoint(
    val timeMillis: Long,
    val mean: Double?,
    val p10: Double?,
    val p25: Double?,
    val p75: Double?,
    val p90: Double?,
)
