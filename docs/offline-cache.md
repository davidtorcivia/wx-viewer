# Offline display cache

Opening previously visited places and ensemble charts uses parsed memory first, then persistent
files. Disk reads, JSON decoding, aggregation, and disk trimming run off the UI thread. An offline
or slow connection never holds cached display data behind an HTTP timeout.

The shared forecast / observation history / ensemble / radar payload store retains display snapshots for up to seven
days within a 64 MiB least-recently-used budget (8 MiB per entry). Fetch time is preserved separately
from access time. A future timestamp after clock rollback, corrupt record, or record older than
seven days is ignored. The parsed forecast LRU holds up to 32 places; parsed ensembles hold up to
96 responses within an estimated 12 MiB budget. Observation history retains up to 32 parsed places. Original observation timestamps determine whether historical points still belong on the chart. The map SDK's ambient tile cache has its own budget.
Settings can clear downloaded display data; a generation guard prevents pending older downloads
from repopulating the cleared store. Forecasts saved by previous app versions remain readable.

Each key includes the normalized server and location, or the server / station / model / cycle /
parameter. A different server, place, or selected historical run cannot reuse an unrelated snapshot.
Automatic latest ensemble views may show the most recent downloaded run from the preceding two
days while the desired latest run refreshes. The saved label identifies its actual run and age.

Cached values are labelled with their original age, including an older-data indication after six
hours. Active ensemble runs refresh after 15 minutes; historical runs are immutable unless manually
refreshed. Independent charts and prior runs render as they arrive. Refresh collectors belong to
the visible screen and cancel when the location, model, cycle, or screen changes. Concurrent requests
for the same item share a bounded lock and reuse a newly completed response. Connectivity changes
retry the visible view without a background polling loop.

These snapshots are for display only. Forecast and official-warning alert paths require live,
non-stale server responses and never silently fall back to downloaded display data.

Device coverage lives in `OfflineCacheE2eTest`: legacy migration, two-day data retention with honest
age, disk reopening after parsed-memory reset, origin/location isolation, and production Activity
navigation into persisted prior-run ensembles against an unreachable origin. Activity relaunch plus
memory reset exercises the cold cache path; it is not a claim of OS process-death coverage.
