# SREF Viewer compatibility

Current reference: `davidtorcivia/sref-viewer` commit `7e1bddc` (checked out September 30, 2026). The client is implemented against these read-only routes:

- `/api/forecast?lat=&lon=`: RTMA `now`, RRFS columnar `hourly`, NBM `daily`, `tz`, building flags and nearest REFS station
- `/api/nowcast?lat=&lon=`: deployed minute radar contract (`time`, `step=60`, 61–121 `dbz` samples, liquid-equivalent `rate`, optional precipitation `kind` and snow mask, HRRR run provenance and precipitation spell start/end/intensity); `p` is the fraction of the spatial patch wet and is never presented as a calibrated probability
- `/api/nowcast/notify?lat=&lon=&within=60`: server onset decision and page-equivalent notification wording; only fresh `notify=true` decisions can alert, with a 5–60-minute user window applied locally
- `/api/history?lat=&lon=`: nearby observed station history
- `/api/geocode?q=` and `/api/geocode?lat=&lon=`
- `/api/radar/frames`, `/api/radar/tile/:time/:z/:x/:y.png`, satellite proxy and frame revision metadata
- `/api/radar/alerts?lat=&lon=&radius=50`: polygon warnings, filtered locally for exact containment and expiry
- `/api/refs/:station/:run/:param?date=`: native ensemble plume input

The Android client does not change backend rain-detection logic. The newly deployed live-rain endpoint was verified on September 30, 2026. After the rollout, commit `a52880c` became visible and was inspected directly: it extends the point outlook to two hours and blends radar into HRRR after the first hour. Commit `099a369` adds the dedicated app notification endpoint. Commit `7e1bddc` adds rain, snow, wet snow, sleet and freezing rain, with liquid-equivalent rate scales and the worst precipitation kind during the whole spell. The client supports both the older 61-sample and newer 121-sample response, and handles omitted all-rain kind arrays separately from explicit unknown kinds.

The notification endpoint gates approaching-precipitation alerts; typed samples and spell summaries then enforce the user's kind and intensity thresholds. Only legacy responses use the raw-reflectivity threshold. The source requests a minimum two-minute polling interval; same-place requests are coalesced across UI, background checks and the foreground watch. Existing rain does not issue another onset notification. Missing/stale/error responses cannot falsely rearm a wet spell or fabricate dry weather. Captured NYC dry responses and explicitly synthetic wet variants are included as device-test fixtures. The raw `p` array is not used for notification thresholds.

Native map nowcast additionally reads `/api/radar/mrms/:time/crop.png`, `flow.png?mean=1`, the radar palette, and optional NEXRAD crops. It follows the source motion encoding and resamples into Mercator for MapLibre `ImageSource` geometry.

No server deployments or existing pull-request merges are part of this client publication. Endpoint incompatibilities should fail visibly or leave data missing, never fabricate conditions.
