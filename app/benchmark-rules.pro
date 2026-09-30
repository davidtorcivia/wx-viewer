# Fixture injection is compiled only into the non-shipping benchmark variant.
# Preserve only the cache field's reflective name; optimizations remain enabled.
-keepclassmembers,allowoptimization class zone.disinfo.wx.data.EnsembleRepository {
    *** cache;
}
-keep,allowoptimization class zone.disinfo.wx.ui.RadarSessions {
    public static ** INSTANCE;
    public *** get(...);
}
