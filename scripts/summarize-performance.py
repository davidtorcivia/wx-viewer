#!/usr/bin/env python3
"""Keep raw samples and summarize same-runner A/B/A without claiming device speed."""
import json
import statistics
import sys
from pathlib import Path

root = Path(sys.argv[1])

def flatten(value):
    if isinstance(value, (int, float)):
        return [value]
    if isinstance(value, list):
        return [n for item in value for n in flatten(item)]
    return []

def percentile(values, q):
    values = sorted(values)
    position = (len(values) - 1) * q
    lo, hi = int(position), min(int(position) + 1, len(values) - 1)
    return values[lo] + (values[hi] - values[lo]) * (position - lo)

legs = {}
for leg in ("baseline", "optimized", "baseline-repeat"):
    metrics = {}
    reports = list((root / leg).rglob("*-benchmarkData.json"))
    if not reports:
        raise SystemExit(f"Missing benchmark JSON for {leg}")
    for report in reports:
        data = json.loads(report.read_text())
        for benchmark in data.get("benchmarks", []):
            name = benchmark["name"]
            for group in ("metrics", "sampledMetrics"):
                for metric, record in benchmark.get(group, {}).items():
                    samples = flatten(record.get("runs", []))
                    if not samples:
                        raise SystemExit(f"Missing raw runs: {leg}/{name}/{metric}")
                    key = f"{name}/{metric}"
                    metrics[key] = {"median": statistics.median(samples),
                                    "p95": percentile(samples, .95),
                                    "sample_count": len(samples), "raw_runs": record["runs"],
                                    "sampled_frames": group == "sampledMetrics"}
                    if metric == "frameOverrunMs":
                        metrics[key]["positive_overrun_percent"] = 100 * sum(v > 0 for v in samples) / len(samples)
    loaded = list((root / leg).rglob("loaded-hero-observed.json"))
    if len(loaded) != 1:
        raise SystemExit(f"Expected one loaded-hero observation report for {leg}")
    samples = json.loads(loaded[0].read_text())["runs"]
    if len(samples) != 3:
        raise SystemExit(f"Expected three loaded-hero runs for {leg}, got {len(samples)}")
    metrics["cachedColdStartup/launchToLoadedHeroObservedMs"] = {
        "median": statistics.median(samples), "p95": percentile(samples, .95),
        "sample_count": len(samples), "raw_runs": samples}
    legs[leg] = metrics

if not (set(legs["baseline"]) == set(legs["optimized"]) == set(legs["baseline-repeat"])):
    raise SystemExit("Benchmark metric sets differ; comparison is invalid")
comparisons = []
for name in sorted(legs["baseline"]):
    stats = ["median"]
    if legs["baseline"][name].get("sampled_frames"):
        stats.append("p95")
    if "positive_overrun_percent" in legs["baseline"][name]:
        stats.append("positive_overrun_percent")
    for stat in stats:
        a, b, a2 = (legs[leg][name][stat] for leg in ("baseline", "optimized", "baseline-repeat"))
        reference = (a + a2) / 2
        unit = "percentage points" if stat == "positive_overrun_percent" else "ms" if name.endswith("Ms") else "count"
        # Negative frame slack has no useful percentage denominator. Report milliseconds;
        # positive-overrun rates are percentages whose differences are percentage points.
        percentage_meaningful = unit != "percentage points" and "frameOverrunMs" not in name and reference > 0
        comparisons.append({"metric": name, "statistic": stat, "unit": unit,
                            "baseline": a, "optimized": b, "baseline_repeat": a2,
                            "delta": b - reference, "baseline_drift": abs(a2 - a),
                            "change_percent": (b - reference) / reference * 100 if percentage_meaningful else None})
summary = {"scope": "Relative diagnostics on one accelerated emulator; not physical-device speed claims",
           "compilation": "Full AOT for every leg", "iterations_per_scenario": 3,
           "fixture": "Identical captured values rebased relative to setup time; alerts/location off; reserved offline endpoint",
           "caution": "Three samples and host drift limit confidence. Inspect raw runs and Perfetto before attributing a change.",
           "legs": legs, "comparison": comparisons}
(root / "comparison.json").write_text(json.dumps(summary, indent=2) + "\n")
lines = ["# Relative Android performance comparison", "", summary["scope"], "",
         "Full AOT; three iterations per scenario; baseline → optimized → baseline repeat.", "",
         "| Metric/statistic | Baseline | Optimized | Baseline repeat | Delta | Baseline drift |",
         "|---|---:|---:|---:|---:|---:|"]
for row in comparisons:
    unit = "pp" if row["unit"] == "percentage points" else row["unit"]
    lines.append(f'| {row["metric"]}/{row["statistic"]} | {row["baseline"]:.2f} | {row["optimized"]:.2f} | {row["baseline_repeat"]:.2f} | {row["delta"]:+.2f} {unit} | {row["baseline_drift"]:.2f} {unit} |')
lines += ["", "Loaded-hero observation samples (ms):"]
for leg in ("baseline", "optimized", "baseline-repeat"):
    lines.append(f'- {leg}: {legs[leg]["cachedColdStartup/launchToLoadedHeroObservedMs"]["raw_runs"]}')
lines += ["", summary["caution"], "", "Frame P95 and positive-overrun rate describe the sampled frames. Three startup observations do not support a strong tail-latency claim. Raw per-iteration samples and Perfetto traces remain alongside this summary."]
(root / "comparison.md").write_text("\n".join(lines) + "\n")
print("\n".join(lines))
