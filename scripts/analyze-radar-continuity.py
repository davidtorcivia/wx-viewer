#!/usr/bin/env python3
"""Fail-closed, host-side analysis of unresampled Android screenrecord clips.

Dependencies: ffmpeg, ffprobe, numpy, Pillow. No screenshot sequences, interpolation,
-r, or fps filter are used: every encoded presentation frame is decoded once for
metrics, with its ffprobe PTS. Spatial signatures never replace full-resolution
black/chroma/detail measurements. Success means *encoded-frame* continuity at the
reported cadence, not proof that the encoder captured every display refresh.

Input: recursively discovered *.capture.json sidecars, schemaVersion=1. elapsedMs
on events is relative to startElapsedRealtimeMs. mapRegion is an unobscured native
map-only rectangle in full-display pixels. The recorder must not scale its video.
The capture starts paused and settled, then includes two >=4s plays, each with at
least three distinct frame_stamp values, and four paired, verified scrubs.

Output: one aggregate JSON, per-capture compact columnar JSON + CSV containing
EVERY frame, and native-resolution before/failure/after strips. Failed and
inconclusive evidence both exit 1. Missing/duplicate layer/font cases fail closed.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

import numpy as np
from PIL import Image, ImageDraw

LAYERS = ("Temperature", "Dew point", "Wind", "Wind gusts", "Clouds", "Precip total",
          "Snow total", "Radar", "Satellite", "Radar + satellite")
FONT_SCALES = (1.0, 2.0)
OPAQUE_LAYERS = frozenset(LAYERS[:4])
MIN_FPS = 24.0
MAX_GAP_MS = 150.0
MAX_P95_GAP_MS = 100.0
MIN_PLAY_SECONDS = 4.0
MIN_PLAYS = 2
MIN_SCRUBS = 4
GRID_ROWS, GRID_COLS = 6, 8
COLUMNS = ("frame", "ptsMs", "elapsedEstimateMs", "gapMs", "meanLuma", "lumaStd",
           "blackFraction", "meanChroma", "colorFraction", "edgeFraction",
           "deltaMean", "deltaFraction", "fieldLostCells", "detailLostCells", "flags")


def problem(code, message, status="failed", first=None, last=None):
    value = {"code": code, "status": status, "message": message}
    if first is not None:
        value.update(firstFrame=int(first), lastFrame=int(first if last is None else last))
    return value


def finite(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def validate_sidecar(data):
    errors = []
    if type(data.get("schemaVersion")) is not int or data["schemaVersion"] != 1:
        errors.append(problem("sidecar_schema", "Expected schemaVersion 1"))
    if data.get("layer") not in LAYERS or not finite(data.get("fontScale")) or data["fontScale"] not in FONT_SCALES:
        errors.append(problem("capture_identity", "Unexpected layer or fontScale"))
    if data.get("completed") is not True or data.get("failure"):
        errors.append(problem("capture_incomplete", str(data.get("failure") or "completed is not true")))
    for key in ("displayWidth", "displayHeight"):
        if not isinstance(data.get(key), int) or isinstance(data.get(key), bool) or data[key] < 32:
            errors.append(problem("display_dimensions", f"Invalid {key}"))
    region = data.get("mapRegion", {})
    if not isinstance(region, dict) or not all(isinstance(region.get(k), int) and not isinstance(region[k], bool)
                                               for k in ("left", "top", "right", "bottom")):
        errors.append(problem("map_region", "mapRegion requires integer left/top/right/bottom"))
    elif (region["left"] < 0 or region["top"] < 0 or region["right"] > data.get("displayWidth", 0)
          or region["bottom"] > data.get("displayHeight", 0) or region["right"] - region["left"] < 32
          or region["bottom"] - region["top"] < 32):
        errors.append(problem("map_region", "Native map crop is out of bounds or smaller than 32 pixels"))
    if not all(finite(data.get(k)) for k in ("startElapsedRealtimeMs", "endElapsedRealtimeMs")):
        errors.append(problem("capture_timing", "Missing finite monotonic capture times"))
    elif data["endElapsedRealtimeMs"] <= data["startElapsedRealtimeMs"]:
        errors.append(problem("capture_timing", "Capture end must follow start"))
    uncertainty = data.get("captureStartUncertaintyMs", 700)
    if not finite(uncertainty) or not 0 <= uncertainty <= 5000:
        errors.append(problem("capture_timing", "captureStartUncertaintyMs must be between 0 and 5000"))
    if not isinstance(data.get("video"), str) or not data["video"]:
        errors.append(problem("video_path", "Missing relative video filename"))
    return errors


def validate_events(data):
    """Validate real paired actions; clock strings alone cannot satisfy any gate."""
    errors, plays, scrubs = [], [], []
    events = data.get("events")
    if not isinstance(events, list):
        return [problem("events_missing", "events must be an array")], [], []
    end_ms = data["endElapsedRealtimeMs"] - data["startElapsedRealtimeMs"]
    previous, play, scrub = -1, None, None
    for event in events:
        if not isinstance(event, dict) or not finite(event.get("elapsedMs")):
            errors.append(problem("event_timing", "Every event needs a finite elapsedMs"))
            continue
        now = event["elapsedMs"]
        if now < previous or now < 0 or now > end_ms + 10:
            errors.append(problem("event_timing", "Events must be ordered within capture monotonic time"))
        previous = now
        kind = event.get("type")
        if kind == "play_start":
            if play is not None:
                errors.append(problem("unpaired_play", "play_start before previous play_end"))
            play = event
        elif kind == "play_end":
            if play is None:
                errors.append(problem("unpaired_play", "play_end without play_start"))
            else:
                stamps = [e.get("stamp") for e in events if isinstance(e, dict)
                          and e.get("type") == "frame_stamp" and finite(e.get("elapsedMs"))
                          and play["elapsedMs"] <= e["elapsedMs"] <= now and e.get("stamp")]
                distinct = len(set(map(str, stamps)))
                item = {"startMs": play["elapsedMs"], "endMs": now,
                        "durationMs": now - play["elapsedMs"], "distinctStamps": distinct,
                        "activeEndMs": min([e["elapsedMs"] for e in events if isinstance(e, dict)
                                            and e.get("type") == "scrub_start" and finite(e.get("elapsedMs"))
                                            and play["elapsedMs"] <= e["elapsedMs"] <= now] or [now])}
                plays.append(item)
                if distinct < 3:
                    errors.append(problem("play_stamps", "Each play needs three distinct frame_stamp observations"))
                play = None
        elif kind == "scrub_start":
            if scrub is not None:
                errors.append(problem("unpaired_scrub", "scrub_start before previous scrub_end"))
            scrub = dict(event, duringPlay=play is not None)
            fraction = event.get("targetFraction")
            if not finite(fraction) or not 0 <= fraction <= 1:
                errors.append(problem("scrub_target", "scrub_start needs targetFraction in [0,1]"))
        elif kind == "scrub_end":
            if scrub is None:
                errors.append(problem("unpaired_scrub", "scrub_end without scrub_start"))
            else:
                verified = event.get("seekVerified") is True or (event.get("expectedStamp") is not None
                            and event.get("stamp") == event["expectedStamp"])
                if not event.get("stamp") or not verified:
                    errors.append(problem("scrub_unverified", "scrub_end needs actual stamp and exact seek verification"))
                if event.get("targetFraction") != scrub.get("targetFraction"):
                    errors.append(problem("scrub_target", "Completed scrub target differs from requested target"))
                scrubs.append({"startMs": scrub["elapsedMs"], "endMs": now,
                               "targetFraction": scrub.get("targetFraction"), "stamp": event.get("stamp"),
                               "verified": verified, "duringPlay": scrub["duringPlay"]})
                scrub = None
    if play is not None or scrub is not None:
        errors.append(problem("unfinished_transition", "Capture ends with an unfinished play or scrub"))
    if len(plays) < MIN_PLAYS or len(scrubs) < MIN_SCRUBS:
        errors.append(problem("transition_coverage", f"Need {MIN_PLAYS} completed plays and {MIN_SCRUBS} completed scrubs"))
    if sum(p["activeEndMs"] - p["startMs"] >= MIN_PLAY_SECONDS * 1000 for p in plays) < MIN_PLAYS:
        errors.append(problem("short_play", "Need two sustained active plays lasting at least four seconds"))
    if not any(s["duringPlay"] for s in scrubs):
        errors.append(problem("playing_scrub_missing", "No completed scrub while playback is active"))
    uncertainty = data.get("captureStartUncertaintyMs", 700)
    if plays and plays[0]["startMs"] < uncertainty + 200:
        errors.append(problem("initial_baseline_missing", "Need settled paused frames before the first play", "inconclusive"))
    return errors, plays, scrubs


def probe_video(video):
    command = ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_streams", "-show_frames",
               "-show_entries", "stream=width,height,avg_frame_rate,r_frame_rate,start_time,duration,nb_frames:"
               "frame=best_effort_timestamp_time,pkt_duration_time", "-of", "json", str(video)]
    result = subprocess.run(command, capture_output=True, text=True, timeout=120)
    if result.returncode:
        raise ValueError("ffprobe failed: " + result.stderr.strip()[-2000:])
    info = json.loads(result.stdout)
    streams = info.get("streams", [])
    if len(streams) != 1:
        raise ValueError("Expected exactly one selected video stream")
    frames = info.get("frames", [])
    if not frames:
        raise ValueError("Video contains no presentation frames")
    pts = []
    for frame in frames:
        try:
            value = float(frame["best_effort_timestamp_time"]) * 1000
        except (KeyError, ValueError, TypeError) as exc:
            raise ValueError("A decoded frame has no presentation timestamp") from exc
        if not math.isfinite(value):
            raise ValueError("Non-finite video timestamp")
        pts.append(value)
    return streams[0], np.asarray(pts, dtype=np.float64)


def decode_frames(video, region):
    """Yield full-resolution cropped RGB frames, with no temporal resampling."""
    w, h = region["right"] - region["left"], region["bottom"] - region["top"]
    command = ["ffmpeg", "-v", "error", "-noautorotate", "-i", str(video), "-map", "0:v:0",
               "-vf", f"format=rgb24,crop={w}:{h}:{region['left']}:{region['top']}:exact=1", "-vsync", "0",
               "-pix_fmt", "rgb24", "-f", "rawvideo", "pipe:1"]
    with tempfile.TemporaryFile() as stderr:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=stderr)
        try:
            size = w * h * 3
            while True:
                raw = process.stdout.read(size)
                if not raw:
                    break
                if len(raw) != size:
                    raise ValueError("Truncated decoded raw frame")
                yield np.frombuffer(raw, np.uint8).reshape((h, w, 3))
            if process.wait(timeout=30):
                stderr.seek(0)
                raise ValueError("ffmpeg failed: " + stderr.read().decode(errors="replace")[-2000:])
        finally:
            process.stdout.close()
            if process.poll() is None:
                process.kill()
                process.wait()


def frame_metrics(rgb):
    # Work on every native pixel. Low-saturation road/map colors do not count as a
    # continuous weather field. Field calibration is deliberately fail-closed.
    pixels = rgb.astype(np.float32)
    high, low = pixels.max(axis=2), pixels.min(axis=2)
    chroma = high - low
    color = (chroma >= 12) & (chroma / np.maximum(high, 1) >= 0.055)
    gray = pixels @ np.asarray([0.2126, 0.7152, 0.0722], np.float32)
    edge = np.maximum(np.abs(gray - np.roll(gray, 1, axis=0)), np.abs(gray - np.roll(gray, 1, axis=1)))
    edge[0, :] = 0
    edge[:, 0] = 0
    black = high <= 5
    values = {"meanLuma": float(gray.mean()), "lumaStd": float(gray.std()),
              "blackFraction": float(black.mean()), "meanChroma": float(chroma.mean()),
              "colorFraction": float(color.mean()), "edgeFraction": float((edge > 7).mean())}
    rows = np.linspace(0, rgb.shape[0], GRID_ROWS + 1, dtype=int)
    cols = np.linspace(0, rgb.shape[1], GRID_COLS + 1, dtype=int)
    cells = []
    for y0, y1 in zip(rows[:-1], rows[1:]):
        for x0, x1 in zip(cols[:-1], cols[1:]):
            sl = np.s_[y0:y1, x0:x1]
            cells.append([chroma[sl].mean(), color[sl].mean(), (edge[sl] > 7).mean(),
                          gray[sl].std(), black[sl].mean()])
    # Only movement signatures are spatially reduced. Chrome is outside the crop.
    signature = np.asarray(Image.fromarray(rgb).resize((64, 48), Image.Resampling.BOX)).astype(np.float32)
    return values, np.asarray(cells), signature


def cadence_metrics(pts, enforce=True):
    errors = []
    if len(pts) < 2:
        return {"decodedFrames": len(pts)}, [problem("too_few_frames", "At least two frames required", "inconclusive")]
    delta = np.diff(pts)
    duration = float(pts[-1] - pts[0])
    positive = delta[delta > 0]
    fps = (len(pts) - 1) * 1000 / duration if duration > 0 else 0
    summary = {"decodedFrames": len(pts), "firstPtsMs": float(pts[0]), "lastPtsMs": float(pts[-1]),
               "durationMs": duration, "effectiveFps": fps,
               "medianGapMs": float(np.median(positive)) if len(positive) else None,
               "p95GapMs": float(np.percentile(positive, 95)) if len(positive) else None,
               "maximumGapMs": float(delta.max()), "duplicateOrBackwardPts": int((delta <= 0).sum()),
               "gapsAboveLimit": int((delta > MAX_GAP_MS + 0.1).sum())}
    if np.any(delta <= 0):
        errors.append(problem("nonmonotonic_pts", "Duplicate or backwards presentation timestamps", "inconclusive"))
    if fps + 0.01 < MIN_FPS:
        errors.append(problem("low_capture_cadence", f"Measured {fps:.2f} fps is below {MIN_FPS:g} fps", "inconclusive"))
    if len(positive) and np.percentile(positive, 95) > MAX_P95_GAP_MS + .1:
        errors.append(problem("irregular_capture_cadence", "95th percentile frame interval exceeds limit", "inconclusive"))
    for i in np.flatnonzero(delta > MAX_GAP_MS + .1):
        errors.append(problem("capture_gap", f"No encoded frame for {delta[i]:.3f} ms", "inconclusive", i, i + 1))
    return summary, errors if enforce else []


def active_cadence(pts, data, plays, scrubs):
    """Pauses may legitimately encode sparsely; active playback may not.

    For play windows, use the conservative core within the sidecar's reported
    recorder startup estimate. This estimate is not frame-synchronized proof. Scrub windows are expanded by that uncertainty: sparse
    transition evidence is inconclusive instead of claiming an unseen seek smooth.
    """
    relative = pts - pts[0]
    uncertainty = data.get("captureStartUncertaintyMs", 700)
    intervals = [("play", p["startMs"] + 150, p.get("activeEndMs", p["endMs"]) - uncertainty - 150)
                 for p in plays]
    intervals += [("scrub", max(0, s["startMs"] - uncertainty), s["endMs"] + 150) for s in scrubs]
    results, errors = [], []
    for kind, start, end in intervals:
        indices = np.flatnonzero((relative >= start) & (relative <= end))
        if len(indices) < 2 or end <= start:
            result = {"decodedFrames": len(indices)}
            current = [problem("active_capture_missing", f"Insufficient encoded frames during {kind}", "inconclusive")]
        else:
            # Include the bracketing frames to expose missing recording intervals.
            if kind == "play":
                first, last = max(0, int(indices[0]) - 1), min(len(pts) - 1, int(indices[-1]) + 1)
            else:
                # Do not charge a preceding/following static VFR pause to a seek.
                first, last = int(indices[0]), int(indices[-1])
            result, current = cadence_metrics(pts[first:last + 1])
            if kind == "scrub":
                # A scrub can settle instantly; its fps is not expected to match a
                # continuously animating play, but it still needs gap-free evidence.
                current = [e for e in current if e["code"] not in ("low_capture_cadence", "irregular_capture_cadence")]
            for error in current:
                if "firstFrame" in error:
                    error["firstFrame"] += first
                    error["lastFrame"] += first
        result.update(type=kind, startMs=start, endMs=end, passed=not current)
        results.append(result)
        errors.extend(current)
    return {"intervals": results, "lowCadenceIntervals": sum(not r["passed"] for r in results)}, errors


def merge_frame_failures(flags):
    failures = []
    for code in sorted({code for row in flags for code in row}):
        indices = [i for i, row in enumerate(flags) if code in row]
        first = last = indices[0]
        for index in indices[1:] + [None]:
            if index is not None and index == last + 1:
                last = index
                continue
            failures.append(problem(code, f"{code.replace('_', ' ')} in {last - first + 1} encoded frame(s)",
                                    first=first, last=last))
            first = last = index
    return failures


def analyze_frames(frames, pts, data, plays):
    """Pure analysis entry point used for regression fault-injection tests."""
    errors, rows, all_cells, signatures = [], [], [], []
    for index, rgb in enumerate(frames):
        metric, cells, signature = frame_metrics(rgb)
        difference = np.abs(signature - signatures[-1]) if signatures else np.zeros_like(signature)
        metric.update(frame=index, ptsMs=float(pts[index]) if index < len(pts) else None,
                      elapsedEstimateMs=float(pts[index] - pts[0]) if index < len(pts) else None,
                      gapMs=float(pts[index] - pts[index - 1]) if 0 < index < len(pts) else 0,
                      deltaMean=float(difference.mean()), deltaFraction=float((difference.max(axis=2) > 5).mean()),
                      fieldLostCells=0, detailLostCells=0, flags=[])
        rows.append(metric)
        all_cells.append(cells)
        signatures.append(signature)
    if len(rows) != len(pts):
        errors.append(problem("decoded_frame_count", f"ffprobe saw {len(pts)} frames, ffmpeg decoded {len(rows)}", "inconclusive"))
    if not rows:
        return rows, {}, errors + [problem("empty_video", "No decoded frames", "inconclusive")]
    usable = min(len(rows), len(pts))
    relative = pts[:usable] - pts[0]
    uncertainty = data.get("captureStartUncertaintyMs", 700)
    baseline_end = min(800, plays[0]["startMs"] - uncertainty - 50) if plays else 250
    baseline_indices = np.flatnonzero(relative <= baseline_end)
    if not len(baseline_indices) or baseline_end < 100:
        errors.append(problem("baseline_insufficient", "No settled paused native frame before the first play", "inconclusive"))
        baseline_indices = np.arange(min(1, usable))
    if not len(baseline_indices):
        return rows, {}, errors + [problem("baseline_insufficient", "No timestamped baseline frames", "inconclusive")]
    base = {key: float(np.median([rows[i][key] for i in baseline_indices]))
            for key in ("meanChroma", "colorFraction", "edgeFraction", "lumaStd", "blackFraction")}
    base_cells = np.median(np.asarray(all_cells)[baseline_indices], axis=0)
    opaque = data["layer"] in OPAQUE_LAYERS
    if base["edgeFraction"] < .002 or base["lumaStd"] < 2:
        errors.append(problem("baseline_map_detail", "Initial map has too little structural detail to establish continuity", "inconclusive"))
    if opaque and (base["meanChroma"] < 14 or base["colorFraction"] < .45):
        errors.append(problem("baseline_field_coverage", "Initial opaque field cannot be distinguished reliably from base-map-only pixels", "inconclusive"))
    for index, row in enumerate(rows):
        cells = all_cells[index]
        flags = row["flags"]
        if row["blackFraction"] > .90:
            flags.append("black_native_view")
        if row["lumaStd"] < 1.5 and row["edgeFraction"] < .001:
            flags.append("flat_native_view")
        # Do not mistake a dark style's normally black map background for failure.
        black_lost = (base_cells[:, 4] < .50) & (cells[:, 4] > .95)
        if black_lost.sum() >= 2:
            flags.append("partial_black_native_view")
        detailed = base_cells[:, 2] >= .008
        detail_lost = detailed & (cells[:, 2] < base_cells[:, 2] * .16) & (cells[:, 3] < 3.0)
        row["detailLostCells"] = int(detail_lost.sum())
        if not opaque and ((row["edgeFraction"] < base["edgeFraction"] * .15 and base["edgeFraction"] >= .008)
                           or detail_lost.sum() >= 4):
            flags.append("map_detail_loss")
        if opaque:
            field_cells = (base_cells[:, 0] >= 14) & (base_cells[:, 1] >= .45)
            field_lost = field_cells & ((cells[:, 0] < np.maximum(8, base_cells[:, 0] * .35))
                                      | (cells[:, 1] < np.minimum(.35, base_cells[:, 1] * .45)))
            row["fieldLostCells"] = int(field_lost.sum())
            if (row["meanChroma"] < max(8, base["meanChroma"] * .35)
                    or row["colorFraction"] < min(.35, base["colorFraction"] * .45)
                    or field_lost.sum() >= 4):
                flags.append("opaque_field_coverage_loss")
    pixel_failures = merge_frame_failures([row["flags"] for row in rows])
    if data["layer"] in {"Wind", "Wind gusts"}:
        # Real native Wind frame260 from run36964452341 retains a dark-blue field
        # at3–4mph yet crosses this same relative-chroma threshold. Preserve every
        # flag, frame span, threshold, strip and nonzero result; report the actual
        # uncertainty rather than claiming that low chroma proves a missing raster.
        for failure in pixel_failures:
            if failure["code"] == "opaque_field_coverage_loss":
                failure["status"] = "inconclusive"
                failure["message"] = ("Wind field coverage crossed the unchanged calibrated threshold; "
                                      "palette or opacity loss is unresolved. A valid dark low-wind "
                                      "palette can trigger this flag; field disappearance is not established.")
    errors.extend(pixel_failures)
    motion = []
    for play in plays:
        # Screenrecord's first PTS can lag launch. Trim both ends conservatively so
        # unrelated scrubs/clock changes cannot rescue frozen native playback.
        indices = np.flatnonzero((relative >= play["startMs"] + 150)
                                 & (relative <= play.get("activeEndMs", play["endMs"]) - uncertainty - 150))
        distinct, changed = [], 0
        for i in indices:
            sig = signatures[i]
            if not distinct or all(float(np.abs(sig - other).mean()) > .8
                                   and float((np.abs(sig - other).max(axis=2) > 5).mean()) > .008
                                   for other in distinct):
                distinct.append(sig)
                if len(distinct) >= 4:
                    break
        for i in indices:
            if rows[i]["deltaMean"] > .8 and rows[i]["deltaFraction"] > .008:
                changed += 1
        item = {"startMs": play["startMs"], "endMs": play["endMs"], "evaluatedFrames": len(indices),
                "distinctMapStates": len(distinct), "changedFrames": changed,
                "required": opaque, "reason": "opaque field playback" if opaque else "naturally transparent or static weather allowed"}
        motion.append(item)
        if opaque and (len(indices) < MIN_FPS or len(distinct) < 3):
            first = int(indices[0]) if len(indices) else 0
            last = int(indices[-1]) if len(indices) else 0
            errors.append(problem("frozen_native_playback", "Opaque native map lacks three distinct pixel states during play; moving clock text is insufficient",
                                  first=first, last=last))
    calibration = {"baselineFrames": len(baseline_indices), "baselineEndMs": baseline_end,
                   "metrics": base, "opaqueFieldRequired": opaque, "motion": motion}
    return rows, calibration, errors


def write_metrics(directory, rows):
    directory.mkdir(parents=True, exist_ok=True)
    compact = []
    for row in rows:
        compact.append([round(row[key], 5) if isinstance(row[key], float) else row[key] for key in COLUMNS])
    (directory / "frames.json").write_text(json.dumps({"columns": COLUMNS, "rows": compact}, separators=(",", ":")))
    with (directory / "frames.csv").open("w", newline="") as output:
        writer = csv.writer(output)
        writer.writerow(COLUMNS)
        for row in compact:
            writer.writerow(["|".join(value) if isinstance(value, list) else value for value in row])
    return {"frameJson": str(directory / "frames.json"), "frameCsv": str(directory / "frames.csv")}


def write_failure_strips(video, region, pts, failures, directory):
    groups = []
    for failure in failures:
        if "firstFrame" not in failure:
            continue
        first, last = failure["firstFrame"], failure["lastFrame"]
        # Include both frames of a two-frame dropout, plus neighboring valid frames.
        selected = sorted({max(0, first - 1), first, min(first + 1, last), last, min(len(pts) - 1, last + 1)})
        groups.append((failure, selected))
    if not groups:
        return
    unique, aliases = {}, {}
    for failure, selected in groups:
        key = tuple(selected)
        if key not in unique:
            unique[key] = (failure, selected)
            aliases[id(failure)] = []
        else:
            aliases[id(unique[key][0])].append(failure)
    groups = list(unique.values())
    # All failures remain in JSON/CSV. Bound expensive image artifacts while
    # preserving one/two-frame examples first and the longest failure range.
    if len(groups) > 8:
        ranked = sorted(groups, key=lambda group: (group[0]["lastFrame"] - group[0]["firstFrame"], group[0]["firstFrame"]))
        chosen = ranked[:7]
        worst = max(groups, key=lambda group: group[0]["lastFrame"] - group[0]["firstFrame"])
        if worst not in chosen:
            chosen.append(worst)
        else:
            chosen.append(next(group for group in ranked if group not in chosen))
        for failure, _ in groups:
            if not any(failure is selected[0] for selected in chosen):
                failure["imageStripOmitted"] = "Eight-strip per-capture artifact limit; exact frame metrics retained"
                for alias in aliases[id(failure)]:
                    alias["imageStripOmitted"] = failure["imageStripOmitted"]
        groups = chosen
    required = {index for _, selected in groups for index in selected}
    # Decode again without resampling only to retain exact native frames for strips.
    images = {}
    for index, rgb in enumerate(decode_frames(video, region)):
        if index in required:
            images[index] = Image.fromarray(rgb)
    for number, (failure, selected) in enumerate(groups):
        selected = [i for i in selected if i in images]
        if not selected:
            continue
        width, height = images[selected[0]].size
        strip = Image.new("RGB", (width * len(selected), height + 32), "#222222")
        draw = ImageDraw.Draw(strip)
        for col, index in enumerate(selected):
            strip.paste(images[index], (col * width, 32))
            draw.text((col * width + 5, 7), f"frame {index} / PTS {pts[index]:.3f} ms", fill="white")
        name = f"failure-{number:03d}-{failure['code']}-{failure['firstFrame']}.png"
        strip.save(directory / name)
        failure["imageStrip"] = str(directory / name)
        failure["stripFrames"] = selected
        for alias in aliases[id(failure)]:
            alias["imageStrip"] = failure["imageStrip"]
            alias["stripFrames"] = selected


def finish_capture(result):
    statuses = {item["status"] for item in result["failures"]}
    result["status"] = "failed" if "failed" in statuses else "inconclusive" if statuses else "passed"
    result["passed"] = result["status"] == "passed"
    return result


def analyze_capture(sidecar, artifact_root):
    result = {"sidecar": str(sidecar), "failures": []}
    try:
        data = json.loads(sidecar.read_text())
        if not isinstance(data, dict):
            raise ValueError("Sidecar must be a JSON object")
        result.update(layer=data.get("layer"), fontScale=data.get("fontScale"), theme=data.get("theme"))
        opaque = isinstance(data.get("layer"), str) and data["layer"] in OPAQUE_LAYERS
        result["coverage"] = {"everyEncodedFrame": False, "nativeMapBackgroundContinuity": False,
                              "opaqueFieldCoverageProven": False, "nativePixelMotionProven": False,
                              "weatherMotionProven": False, "weatherFrameIdentityProven": False,
                              "limitation": ("Pixel changes prove native map activity only. Wind particles can move over a frozen raster. Raster timestamp identity, meteorological correctness, and every physical display refresh are not proven."
                                             if opaque else "Naturally transparent/static weather is allowed. Only native map/background continuity is checked; weather presence, handoff correctness, and weather motion are not proven.")}
        result["failures"].extend(validate_sidecar(data))
        fatal_codes = {"sidecar_schema", "display_dimensions", "map_region", "capture_timing", "video_path", "capture_identity"}
        if any(error["code"] in fatal_codes for error in result["failures"]):
            return finish_capture(result)
        video = (sidecar.parent / data["video"]).resolve()
        if Path(data["video"]).is_absolute() or not video.is_relative_to(sidecar.parent.resolve()):
            raise ValueError("Video must be a relative path inside the sidecar directory")
        if not video.is_file():
            raise ValueError("Native capture video is missing")
        result["video"] = str(video)
        errors, plays, scrubs = validate_events(data)
        result["failures"].extend(errors)
        stream, pts = probe_video(video)
        if (stream.get("width"), stream.get("height")) != (data["displayWidth"], data["displayHeight"]):
            result["failures"].append(problem("scaled_video", "Video dimensions differ from actual display; native-pixel proof is unavailable", "inconclusive"))
            return finish_capture(result)
        summary, errors = cadence_metrics(pts, enforce=False)
        if summary.get("duplicateOrBackwardPts"):
            errors.append(problem("nonmonotonic_pts", "Duplicate or backwards presentation timestamps", "inconclusive"))
        active, active_errors = active_cadence(pts, data, plays, scrubs)
        summary["activeCadence"] = active
        result["failures"].extend(errors + active_errors)
        result["summary"] = dict(summary, completedPlays=len(plays), completedScrubs=len(scrubs), plays=plays, scrubs=scrubs,
                                 captureStartUncertaintyMs=data.get("captureStartUncertaintyMs", 700),
                                 timingAlignment=data.get("timingAlignment", "assumed-startup-window-not-frame-synchronized"),
                                 eventAlignmentExact=False,
                                 mapRegion=data["mapRegion"], sourceReportedFrameRate=stream.get("r_frame_rate"))
        duration = data["endElapsedRealtimeMs"] - data["startElapsedRealtimeMs"]
        uncertainty = data.get("captureStartUncertaintyMs", 700)
        try:
            container_duration = float(stream.get("duration", 0)) * 1000
        except (TypeError, ValueError):
            container_duration = 0
        if not math.isfinite(container_duration):
            container_duration = 0
        summary["containerDurationMs"] = container_duration
        result["summary"]["containerDurationMs"] = container_duration
        required_end = max([p["endMs"] for p in plays] + [s["endMs"] for s in scrubs] + [0])
        result["summary"]["lastRequiredActionMs"] = required_end
        # A last unchanged paused frame can have no newly encoded sample. Check
        # container duration and coverage of the final action, not the static tail.
        if max(summary.get("durationMs", 0), container_duration) < required_end - uncertainty - 300:
            result["failures"].append(problem("truncated_capture", "Encoded clip does not cover the final play/pause/scrub action within recorder-start uncertainty", "inconclusive"))
        if summary.get("durationMs", 0) > duration + 1000:
            result["failures"].append(problem("capture_timing_mismatch", "Encoded clip is longer than sidecar capture interval", "inconclusive"))
        slug = re.sub(r"[^a-z0-9]+", "-", str(data["layer"]).lower()).strip("-")
        identity = hashlib.sha256(str(sidecar.resolve()).encode()).hexdigest()[:8]
        directory = artifact_root / f"{slug}-{data['fontScale']:g}x-{identity}"
        rows, calibration, errors = analyze_frames(decode_frames(video, data["mapRegion"]), pts, data, plays)
        result["failures"].extend(errors)
        result["calibration"] = calibration
        result["artifacts"] = write_metrics(directory, rows)
        write_failure_strips(video, data["mapRegion"], pts, result["failures"], directory)
        # Retain identity of the actual video consumed, without inventing device evidence.
        digest = hashlib.sha256()
        with video.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        result["videoSha256"] = digest.hexdigest()
        result["coverage"]["everyEncodedFrame"] = len(rows) == len(pts)
        adequate = not any(f["status"] == "inconclusive" for f in result["failures"])
        result["coverage"]["nativeMapBackgroundContinuity"] = adequate and not any(
            f["code"] in {"black_native_view", "partial_black_native_view", "flat_native_view", "map_detail_loss"}
            for f in result["failures"])
        result["coverage"]["opaqueFieldCoverageProven"] = opaque and adequate and not any(
            f["code"] == "opaque_field_coverage_loss" for f in result["failures"])
        result["coverage"]["nativePixelMotionProven"] = opaque and adequate and bool(calibration["motion"]) and not any(
            f["code"] == "frozen_native_playback" for f in result["failures"])
        result["coverage"]["weatherMotionProven"] = (data["layer"] in ("Temperature", "Dew point")
                                                     and result["coverage"]["nativePixelMotionProven"])

    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as exc:
        result["failures"].append(problem("unreadable_evidence", str(exc), "inconclusive"))
    return finish_capture(result)


def expected_cases(shard_count=1, shard_index=0):
    if not 1 <= shard_count <= len(LAYERS) or not 0 <= shard_index < shard_count:
        raise ValueError("Require 1 <= shard-count <= 10 and 0 <= shard-index < shard-count")
    return {(layer, scale) for i, layer in enumerate(LAYERS) if i % shard_count == shard_index for scale in FONT_SCALES}


def validate_inventory(captures, expected):
    seen, failures = set(), []
    for capture in captures:
        identity = (capture.get("layer"), capture.get("fontScale"))
        if not isinstance(identity[0], str) or not finite(identity[1]):
            failures.append(problem("invalid_capture_identity", "Capture has no valid layer/font identity"))
            continue
        if identity in seen:
            failures.append(problem("duplicate_capture", f"Duplicate layer/font capture: {identity}"))
        if identity not in expected:
            failures.append(problem("unexpected_capture", f"Unexpected layer/font capture for this shard: {identity}"))
        seen.add(identity)
    for identity in sorted(expected - seen):
        failures.append(problem("missing_capture", f"Missing layer/font capture: {identity}"))
    return failures


def analyze_root(root, output, shard_count=1, shard_index=0):
    expected = expected_cases(shard_count, shard_index)
    report = {"schemaVersion": 1, "analysis": "native-encoded-frame-continuity", "shardCount": shard_count,
              "shardIndex": shard_index, "expectedCaptures": len(expected), "fontScales": list(FONT_SCALES),
              "thresholds": {"minimumEffectiveFps": MIN_FPS, "maximumFrameGapMs": MAX_GAP_MS,
                             "maximumP95FrameGapMs": MAX_P95_GAP_MS, "minimumPlays": MIN_PLAYS,
                             "minimumPlaySeconds": MIN_PLAY_SECONDS, "minimumScrubs": MIN_SCRUBS},
              "scope": "Every encoded frame at original PTS. Not proof of every physical display refresh; inadequate cadence is inconclusive.",
              "captures": [], "failures": []}
    missing_tools = [name for name in ("ffmpeg", "ffprobe") if not shutil.which(name)]
    if missing_tools:
        report["failures"].append(problem("missing_decoder", "Missing " + ", ".join(missing_tools), "inconclusive"))
    else:
        sidecars = sorted(root.rglob("*.capture.json")) if root.is_dir() else [root]
        for sidecar in sidecars:
            report["captures"].append(analyze_capture(sidecar, output.parent / (output.stem + "-frames")))
        report["failures"].extend(validate_inventory(report["captures"], expected))
    all_failures = report["failures"] + [f for capture in report["captures"] for f in capture["failures"]]
    report["passed"] = not all_failures
    report["status"] = "failed" if any(f["status"] == "failed" for f in all_failures) else "inconclusive" if all_failures else "passed"
    report["analyzedCaptures"] = len(report["captures"])
    report["decodedFrames"] = sum(c.get("summary", {}).get("decodedFrames", 0) for c in report["captures"])
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n")
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("root", type=Path, help="Directory containing native MP4 capture sidecars")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--shard-count", type=int, default=1)
    parser.add_argument("--shard-index", type=int, default=0)
    args = parser.parse_args(argv)
    try:
        report = analyze_root(args.root, args.output, args.shard_count, args.shard_index)
    except ValueError as exc:
        parser.error(str(exc))
    print(f"Radar continuity: {report['status']} ({report['analyzedCaptures']}/{report['expectedCaptures']} captures, "
          f"{report['decodedFrames']} decoded frames); report: {args.output}")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
