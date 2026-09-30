#!/usr/bin/env python3
"""Overlay identical benchmark build/test inputs onto an isolated baseline checkout."""
import argparse
import difflib
import hashlib
import json
import shutil
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--harness", type=Path, required=True)
parser.add_argument("--baseline", type=Path, required=True)
args = parser.parse_args()
harness, baseline = args.harness.resolve(), args.baseline.resolve()
if baseline == harness or harness in baseline.parents:
    raise SystemExit("Baseline must be a separate checkout outside the working source tree")
main = baseline / "app/src/main"
before = {str(p.relative_to(baseline)): hashlib.sha256(p.read_bytes()).hexdigest()
          for p in main.rglob("*") if p.is_file()}
record_path = baseline / "benchmark-overlay-verification.json"
prior = json.loads(record_path.read_text()) if record_path.exists() else {}
original = prior.get("original_production_sha256", prior.get("production_source_sha256", before))
expected = prior.get("instrumented_production_sha256", original)
if before != expected:
    raise SystemExit("Isolated baseline production source changed outside the recorded overlay")
files = ["build.gradle.kts", "settings.gradle.kts", "gradle.properties",
         "app/build.gradle.kts", "app/benchmark-rules.pro"]
for name in files:
    shutil.copy2(harness / name, baseline / name)
for name in ("app/src/benchmark", "macrobenchmark"):
    source = harness / name
    destination = baseline / name
    shutil.copytree(source, destination, dirs_exist_ok=True,
                    ignore=shutil.ignore_patterns("build", ".gradle"))
probe_path = baseline / "app/src/main/java/zone/disinfo/wx/ui/WebWeatherCharts.kt"
source = probe_path.read_text()
instrumented = source
probe = '''
                    stateDescription = selectedTime?.let {
                        "Selected ${chartWeekday(it, zone, nowMillis)} ${chartHour(it, zone, units = units)}"
                    } ?: "Current forecast"
'''
if '"Current forecast"' not in instrumented:
    for anchor in ('contentDescription = "Temperature and wind for the next 48 hours"',
                   '"The last 24 hours observed and the next 24 forecast, as a spiral colored by temperature"'):
        if instrumented.count(anchor) != 1:
            raise SystemExit("Baseline chart probe anchor changed; inspect rather than patch broadly")
        instrumented = instrumented.replace(anchor, anchor + probe, 1)
    if "import androidx.compose.ui.semantics.stateDescription" not in instrumented:
        instrumented = instrumented.replace("import androidx.compose.ui.semantics.semantics",
                                             "import androidx.compose.ui.semantics.stateDescription\nimport androidx.compose.ui.semantics.semantics", 1)
    probe_path.write_text(instrumented)
    patch = "".join(difflib.unified_diff(source.splitlines(True), instrumented.splitlines(True),
                                      fromfile="original/WebWeatherCharts.kt", tofile="instrumented/WebWeatherCharts.kt"))
    (baseline / "benchmark-semantic-probe.patch").write_text(patch)
after = {str(p.relative_to(baseline)): hashlib.sha256(p.read_bytes()).hexdigest()
         for p in main.rglob("*") if p.is_file()}
changed = [name for name in original if original[name] != after[name]]
if changed != [str(probe_path.relative_to(baseline))]:
    raise SystemExit(f"Unexpected baseline production changes: {changed}")
record = {"original_production_sha256": original, "instrumented_production_sha256": after,
          "read_only_probe": "Hourly/spiral selected-time accessibility stateDescription; identical to candidate, no drawing/gesture algorithm change",
          "probe_patch": "benchmark-semantic-probe.patch",
          "common_overlay": files + ["app/src/benchmark/**", "macrobenchmark/**"],
          "purpose": "Same benchmark version, signing configuration, optimization flags, fixture and gestures"}
record_path.write_text(json.dumps(record, indent=2) + "\n")
print(f"Applied common harness; {len(original)-1} production files unchanged, one recorded read-only chart semantics probe")
