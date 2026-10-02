"""Host analyzer regression tests. Generated pixels/videos are fault-injection
fixtures, never native-device validation evidence. Run via unittest discovery.
"""
import copy
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

try:
    import numpy as np
    from PIL import Image
except ImportError as exc:
    # Ordinary build hosts may omit optional image dependencies. The native
    # continuity lane installs pinned dependencies and rejects ALL test skips.
    raise unittest.SkipTest(f"Radar image-analysis dependencies unavailable: {exc}")

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("radar_continuity", HERE.parent / "analyze-radar-continuity.py")
continuity = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(continuity)


def fixture_metadata(layer="Temperature", width=128, height=96):
    events = []

    def event(ms, kind, **extra):
        events.append(dict(elapsedMs=ms, type=kind, **extra))

    def seek(ms, fraction):
        event(ms, "scrub_start", stamp=f"before-{ms}", targetFraction=fraction)
        event(ms + 100, "scrub_end", stamp=f"target-{ms}", targetFraction=fraction, seekVerified=True)

    for start in (1000, 8000):
        event(start, "play_start", stamp=f"start-{start}")
        for offset in (200, 1200, 2200, 3200, 4200):
            event(start + offset, "frame_stamp", stamp=f"stamp-{start + offset}")
        seek(start + 5000, .8)
        event(start + 5300, "play_end", stamp=f"target-{start + 5000}")
        seek(start + 5600, .2)
    return {"schemaVersion": 1, "layer": layer, "fontScale": 1.0, "theme": "dark", "video": "native.mp4",
            "mapRegion": {"left": 0, "top": 0, "right": width, "bottom": height},
            "displayWidth": width, "displayHeight": height, "startElapsedRealtimeMs": 100000,
            "endElapsedRealtimeMs": 114000, "captureStartUncertaintyMs": 0,
            "completed": True, "failure": None, "events": events}


def field_image(state=0, width=128, height=96, base_only=False):
    y, x = np.indices((height, width))
    image = np.empty((height, width, 3), np.uint8)
    if base_only:
        image[:] = [18, 20, 22]  # Dark, neutral base map, still with roads/detail.
    else:
        image[:, :, 0] = 65 + state * 5 + (x // 12) % 3
        image[:, :, 1] = 145 + (y // 12) % 4
        image[:, :, 2] = 190 - state * 3
    roads = (x % 23 == 0) | (y % 19 == 0) | ((x + y) % 37 == 0)
    image[roads] = [100, 102, 104] if base_only else [35, 45, 55]
    return image


def fixture_frames(count=420, layer="Temperature", frozen=False):
    return [field_image(0 if frozen else (index // 15) % 8, base_only=layer not in continuity.OPAQUE_LAYERS)
            for index in range(count)]


def evaluate(frames, layer="Temperature", pts=None):
    data = fixture_metadata(layer)
    errors, plays, _ = continuity.validate_events(data)
    if errors:
        raise AssertionError(errors)
    pts = np.arange(len(frames), dtype=float) * 1000 / 30 if pts is None else pts
    return continuity.analyze_frames(frames, pts, data, plays)


class PixelFaultInjectionTest(unittest.TestCase):
    def test_healthy_opaque_pixels_and_cadence_pass(self):
        for layer in continuity.OPAQUE_LAYERS:
            with self.subTest(layer=layer):
                rows, calibration, failures = evaluate(fixture_frames(layer=layer), layer)
                self.assertFalse(failures, failures)
                self.assertEqual(len(rows), 420)
                self.assertTrue(calibration["opaqueFieldRequired"])
                self.assertTrue(all(m["distinctMapStates"] >= 3 for m in calibration["motion"]))

    def test_one_and_two_frame_black_dropouts_all_ten_layers(self):
        for layer in continuity.LAYERS:
            for length in (1, 2):
                with self.subTest(layer=layer, frames=length):
                    frames = fixture_frames(layer=layer)
                    for index in range(75, 75 + length):
                        frames[index] = np.zeros_like(frames[index])
                    rows, _, failures = evaluate(frames, layer)
                    exact = [f for f in failures if f["code"] == "black_native_view"]
                    self.assertEqual([(f["firstFrame"], f["lastFrame"]) for f in exact], [(75, 74 + length)])
                    self.assertEqual(len(rows), len(frames))

    def test_one_and_two_frame_base_only_dropouts_all_opaque_layers(self):
        for layer in continuity.OPAQUE_LAYERS:
            for length in (1, 2):
                with self.subTest(layer=layer, frames=length):
                    frames = fixture_frames()
                    for index in range(89, 89 + length):
                        frames[index] = field_image(base_only=True)
                    _, _, failures = evaluate(frames, layer)
                    exact = [f for f in failures if f["code"] == "opaque_field_coverage_loss"]
                    self.assertEqual([(f["firstFrame"], f["lastFrame"]) for f in exact], [(89, 88 + length)])

    def test_real_native_low_wind_palette_stays_flagged_inconclusive(self):
        fixture = HERE / "fixtures"
        source = json.loads((fixture / "wind-native-source.json").read_text())
        self.assertEqual(source["workflowRun"], 36964452341)
        reference = np.asarray(Image.open(fixture / "wind-native-reference.png").convert("RGB"))
        low = np.asarray(Image.open(fixture / "wind-native-low-chroma.png").convert("RGB"))
        self.assertEqual(reference.shape, low.shape)
        # Only reporting changes: feed the same two unaltered native crops through
        # the real pixel detector and require its original coverage flag to remain.
        for layer in ("Wind", "Wind gusts"):
            with self.subTest(layer=layer):
                data = fixture_metadata(layer=layer, width=reference.shape[1], height=reference.shape[0])
                rows, _, failures = continuity.analyze_frames([reference, low], np.asarray([0., 30000.]), data, [])
                self.assertIn("opaque_field_coverage_loss", rows[1]["flags"])
                coverage = [f for f in failures if f["code"] == "opaque_field_coverage_loss"]
                self.assertTrue(coverage)
                self.assertTrue(all(f["status"] == "inconclusive" for f in coverage))
                self.assertTrue(all("palette or opacity" in f["message"] for f in coverage))
                result = continuity.finish_capture({"failures": failures})
                self.assertEqual(result["status"], "inconclusive")
                self.assertFalse(result["passed"])
                black_rows, _, black_failures = continuity.analyze_frames(
                    [reference, np.zeros_like(reference)], np.asarray([0., 30000.]), data, [])
                self.assertIn("black_native_view", black_rows[1]["flags"])
                self.assertEqual(continuity.finish_capture({"failures": black_failures})["status"], "failed")

    def test_partial_field_dropout_cannot_hide_in_global_average(self):
        frames = fixture_frames()
        frames[100] = frames[100].copy()
        frames[100][:48, :64] = field_image(base_only=True)[:48, :64]
        rows, _, failures = evaluate(frames)
        self.assertGreaterEqual(rows[100]["fieldLostCells"], 4)
        self.assertIn("opaque_field_coverage_loss", {f["code"] for f in failures})

    def test_colored_flat_native_loss_on_transparent_layers(self):
        for layer in continuity.LAYERS[4:]:
            frames = fixture_frames(layer=layer)
            frames[110] = np.full_like(frames[110], [80, 110, 145])
            _, _, failures = evaluate(frames, layer)
            self.assertIn("flat_native_view", {f["code"] for f in failures})
            self.assertIn("map_detail_loss", {f["code"] for f in failures})

    def test_frozen_native_field_fails_despite_advancing_event_clocks(self):
        for layer in continuity.OPAQUE_LAYERS:
            _, calibration, failures = evaluate(fixture_frames(frozen=True), layer)
            self.assertEqual(len([f for f in failures if f["code"] == "frozen_native_playback"]), 2)
            self.assertTrue(all(m["distinctMapStates"] == 1 for m in calibration["motion"]))

    def test_legitimately_clear_radar_snow_accept_static_detailed_map(self):
        for layer in continuity.LAYERS[4:]:
            _, calibration, failures = evaluate(fixture_frames(layer=layer, frozen=True), layer)
            self.assertFalse(failures, failures)
            self.assertFalse(calibration["opaqueFieldRequired"])
            self.assertTrue(all(not m["required"] for m in calibration["motion"]))

    def test_weak_initial_field_is_inconclusive_not_calibrated_as_good(self):
        frames = [field_image(base_only=True) for _ in range(420)]
        _, _, failures = evaluate(frames)
        weak = [f for f in failures if f["code"] == "baseline_field_coverage"]
        self.assertEqual(weak[0]["status"], "inconclusive")

    def test_one_settled_native_baseline_frame_is_valid_for_static_vfr_pause(self):
        frames = fixture_frames()
        indices = [0] + list(range(26, len(frames)))
        pts = np.asarray(indices, dtype=float) * 1000 / 30
        _, calibration, failures = evaluate([frames[i] for i in indices], pts=pts)
        self.assertEqual(calibration["baselineFrames"], 1)
        self.assertFalse(failures, failures)

    def test_codec_like_noise_does_not_fake_map_motion(self):
        rng = np.random.default_rng(5)
        base = field_image().astype(np.int16)
        frames = [np.clip(base + rng.integers(-3, 4, base.shape), 0, 255).astype(np.uint8) for _ in range(420)]
        _, _, failures = evaluate(frames)
        self.assertIn("frozen_native_playback", {f["code"] for f in failures})

    def test_decoded_frame_count_mismatch_is_inconclusive(self):
        frames = fixture_frames(count=30)
        _, _, failures = evaluate(frames, pts=np.arange(31) * 1000 / 30)
        self.assertIn("decoded_frame_count", {f["code"] for f in failures})


class TimingAndContractTest(unittest.TestCase):
    def test_real_pts_cadence_and_subframe_precision(self):
        pts = np.arange(420) * 1000 / 30 + 250.125
        summary, failures = continuity.cadence_metrics(pts)
        self.assertFalse(failures)
        self.assertAlmostEqual(summary["effectiveFps"], 30)
        self.assertEqual(summary["firstPtsMs"], 250.125)

    def test_low_cadence_duplicate_and_backward_pts_cannot_pass(self):
        for pts, code in ((np.arange(140) * 100, "low_capture_cadence"),
                          (np.array([0, 33, 33, 66]), "nonmonotonic_pts"),
                          (np.array([0, 33, 20, 66]), "nonmonotonic_pts")):
            with self.subTest(code=code):
                _, failures = continuity.cadence_metrics(pts)
                self.assertIn(code, {f["code"] for f in failures})
                self.assertTrue(all(f["status"] == "inconclusive" for f in failures))

    def test_single_recording_gap_is_not_smoothed_away(self):
        pts = np.arange(420) * 1000 / 30
        pts[120:] += 250
        summary, failures = continuity.cadence_metrics(pts)
        gap = [f for f in failures if f["code"] == "capture_gap"]
        self.assertEqual([(f["firstFrame"], f["lastFrame"]) for f in gap], [(119, 120)])
        self.assertGreater(summary["effectiveFps"], 24)

    def test_sparse_pause_is_allowed_but_sparse_active_play_is_not(self):
        data = fixture_metadata()
        _, plays, scrubs = continuity.validate_events(data)
        pts = np.arange(420) * 1000 / 30
        # Genuinely unchanged paused reference before play may have few frames.
        pts = pts[(pts < 1) | (pts >= 850)]
        _, failures = continuity.active_cadence(pts, data, plays, scrubs)
        self.assertFalse(failures, failures)
        pts = pts[(pts < 2200) | (pts > 2700)]
        _, failures = continuity.active_cadence(pts, data, plays, scrubs)
        self.assertIn("capture_gap", {f["code"] for f in failures})

    def test_static_pause_before_seek_is_not_a_transition_capture_gap(self):
        data = fixture_metadata()
        _, plays, scrubs = continuity.validate_events(data)
        pts = np.arange(420) * 1000 / 30
        pts = pts[(pts < 6140) | (pts > 6650)]
        _, failures = continuity.active_cadence(pts, data, plays, scrubs)
        self.assertFalse(failures, failures)

    def test_extra_three_second_replay_does_not_replace_sustained_plays(self):
        data = fixture_metadata()
        data["endElapsedRealtimeMs"] += 4500
        data["events"] += [dict(elapsedMs=14500, type="play_start", stamp="extra-start")]
        data["events"] += [dict(elapsedMs=14500 + i * 900, type="frame_stamp", stamp=f"extra-{i}")
                           for i in range(1, 4)]
        data["events"] += [dict(elapsedMs=17500, type="play_end", stamp="extra-end")]
        failures, plays, _ = continuity.validate_events(data)
        self.assertFalse(failures, failures)
        self.assertEqual(len(plays), 3)

    def test_clock_events_alone_cannot_prove_transitions(self):
        data = fixture_metadata()
        data["events"] = [e for e in data["events"] if e["type"] == "frame_stamp"]
        failures, plays, scrubs = continuity.validate_events(data)
        self.assertFalse(plays or scrubs)
        self.assertIn("transition_coverage", {f["code"] for f in failures})

    def test_unpaired_unverified_short_and_unchanged_events_fail(self):
        changes = {
            "unpaired": lambda d: d["events"].pop(0),
            "unverified": lambda d: [e.pop("seekVerified", None) for e in d["events"]],
            "unchanged": lambda d: [e.update(stamp="same") for e in d["events"]],
            "short": lambda d: [e.update(elapsedMs=e["elapsedMs"] / 3) for e in d["events"]],
            "out_of_order": lambda d: d["events"].reverse(),
            "no_playing_seek": lambda d: d["events"].__setitem__(slice(None), [e for e in d["events"] if e["type"] not in ("scrub_start", "scrub_end")]),
        }
        for name, change in changes.items():
            data = fixture_metadata()
            change(data)
            with self.subTest(name=name):
                self.assertTrue(continuity.validate_events(data)[0])

    def test_inventory_exactly_twenty_and_disjoint_shards(self):
        expected = continuity.expected_cases()
        self.assertEqual(len(expected), 20)
        captures = [dict(layer=layer, fontScale=scale) for layer, scale in expected]
        self.assertFalse(continuity.validate_inventory(captures, expected))
        self.assertTrue(continuity.validate_inventory(captures[:-1], expected))
        self.assertTrue(continuity.validate_inventory(captures + captures[:1], expected))
        self.assertTrue(continuity.validate_inventory(captures + [dict(layer="Other", fontScale=1)], expected))
        shards = [continuity.expected_cases(3, i) for i in range(3)]
        self.assertEqual(set.union(*shards), expected)
        self.assertEqual(sum(map(len, shards)), len(expected))

    def test_incomplete_and_invalid_crop_fail(self):
        for mutation in (lambda d: d.update(completed=False), lambda d: d.update(failure="device crashed"),
                         lambda d: d["mapRegion"].update(right=9999), lambda d: d.update(fontScale=1.5)):
            data = fixture_metadata()
            mutation(data)
            self.assertTrue(continuity.validate_sidecar(data))


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "ffmpeg/ffprobe unavailable")
class NativeDecodePipelineTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def encode(self, frames, fps=30, name="native.mp4"):
        height, width, _ = frames[0].shape
        path = self.root / name
        command = ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24",
                   "-s", f"{width}x{height}", "-r", str(fps), "-i", "pipe:0", "-c:v", "libx264",
                   "-preset", "ultrafast", "-crf", "18", "-pix_fmt", "yuv420p", str(path)]
        subprocess.run(command, input=b"".join(frame.tobytes() for frame in frames), check=True, capture_output=True)
        return path

    def test_all_encoded_frames_with_native_pts_survive_real_mp4_decode(self):
        frames = fixture_frames(count=120)
        frames[61] = np.zeros_like(frames[61])
        frames[62] = np.zeros_like(frames[62])
        path = self.encode(frames)
        stream, pts = continuity.probe_video(path)
        decoded = list(continuity.decode_frames(path, fixture_metadata()["mapRegion"]))
        self.assertEqual(len(decoded), len(frames))
        self.assertEqual(len(pts), len(frames))
        self.assertEqual((stream["width"], stream["height"]), (128, 96))
        rows, _, failures = evaluate(decoded, pts=pts)
        black = [f for f in failures if f["code"] == "black_native_view"]
        self.assertEqual([(f["firstFrame"], f["lastFrame"]) for f in black], [(61, 62)])
        self.assertAlmostEqual(pts[62] - pts[61], 1000 / 30, places=2)
        paths = continuity.write_metrics(self.root / "metrics", rows)
        metrics = json.loads(Path(paths["frameJson"]).read_text())
        self.assertEqual(len(metrics["rows"]), 120)
        self.assertEqual(len(Path(paths["frameCsv"]).read_text().splitlines()), 121)
        continuity.write_failure_strips(path, fixture_metadata()["mapRegion"], pts, black, self.root)
        self.assertEqual(black[0]["stripFrames"], [60, 61, 62, 63])
        with Image.open(black[0]["imageStrip"]) as strip:
            self.assertEqual(strip.size, (128 * 4, 96 + 32))

    def test_h264_encoded_base_only_flicker_is_preserved(self):
        frames = fixture_frames(count=120)
        frames[70] = field_image(base_only=True)
        frames[71] = field_image(base_only=True)
        path = self.encode(frames)
        _, pts = continuity.probe_video(path)
        _, _, failures = continuity.analyze_frames(
            continuity.decode_frames(path, fixture_metadata()["mapRegion"]), pts,
            fixture_metadata(), continuity.validate_events(fixture_metadata())[1])
        missing = [f for f in failures if f["code"] == "opaque_field_coverage_loss"]
        self.assertEqual([(f["firstFrame"], f["lastFrame"]) for f in missing], [(70, 71)])

    def test_wind_particles_do_not_claim_weather_raster_identity_or_motion(self):
        # Negative proof fixture: constant weather color, moving native particles.
        # Native activity is visible, but raster-frame advancement is NOT proven.
        frames = []
        for index in range(420):
            frame = field_image()
            for particle in range(10):
                x = (particle * 13 + index * 3) % 126
                y = (particle * 11 + index * 2) % 72
                frame[y:y + 24, x:x + 2] = [15, 30, 45]
            frames.append(frame)
        self.encode(frames)
        sidecar = self.root / "wind.capture.json"
        sidecar.write_text(json.dumps(fixture_metadata("Wind")))
        result = continuity.analyze_capture(sidecar, self.root / "artifacts")
        self.assertTrue(result["coverage"]["nativePixelMotionProven"], result["failures"])
        self.assertFalse(result["coverage"]["weatherMotionProven"])
        self.assertFalse(result["coverage"]["weatherFrameIdentityProven"])
        self.assertIn("frozen raster", result["coverage"]["limitation"])

    def test_odd_native_pixel_crop_is_not_rounded_to_codec_chroma_grid(self):
        path = self.encode(fixture_frames(count=3))
        full = list(continuity.decode_frames(path, fixture_metadata()["mapRegion"]))
        region = {"left": 3, "top": 5, "right": 98, "bottom": 74}
        cropped = list(continuity.decode_frames(path, region))
        self.assertEqual(cropped[0].shape, (69, 95, 3))
        np.testing.assert_array_equal(cropped[0], full[0][5:74, 3:98])

    def test_crop_excludes_moving_clock_and_cannot_rescue_frozen_native_map(self):
        frames = []
        for index in range(420):
            frame = np.zeros((128, 128, 3), np.uint8)
            frame[:96] = field_image()
            frame[96:] = [index % 255, (index * 3) % 255, (index * 7) % 255]
            frames.append(frame)
        path = self.encode(frames)
        _, pts = continuity.probe_video(path)
        decoded = continuity.decode_frames(path, fixture_metadata()["mapRegion"])
        _, _, failures = continuity.analyze_frames(
            decoded, pts, fixture_metadata(), continuity.validate_events(fixture_metadata())[1])
        self.assertIn("frozen_native_playback", {f["code"] for f in failures})

    def test_real_capture_report_and_fail_closed_inventory(self):
        self.encode(fixture_frames())
        sidecar = self.root / "one.capture.json"
        sidecar.write_text(json.dumps(fixture_metadata()))
        result = continuity.analyze_capture(sidecar, self.root / "artifacts")
        self.assertTrue(result["passed"], result["failures"])
        self.assertEqual(result["summary"]["decodedFrames"], 420)
        self.assertEqual(len(result["videoSha256"]), 64)
        report = continuity.analyze_root(self.root, self.root / "report.json")
        self.assertFalse(report["passed"])
        self.assertEqual(len([f for f in report["failures"] if f["code"] == "missing_capture"]), 19)

    def test_scaled_video_is_inconclusive(self):
        self.encode(fixture_frames(count=10))
        data = fixture_metadata(width=256, height=192)
        sidecar = self.root / "one.capture.json"
        sidecar.write_text(json.dumps(data))
        result = continuity.analyze_capture(sidecar, self.root / "artifacts")
        self.assertFalse(result["passed"])
        self.assertIn("scaled_video", {f["code"] for f in result["failures"]})

    def test_truncated_capture_never_reports_smooth(self):
        self.encode(fixture_frames(count=30))
        sidecar = self.root / "one.capture.json"
        sidecar.write_text(json.dumps(fixture_metadata()))
        result = continuity.analyze_capture(sidecar, self.root / "artifacts")
        self.assertFalse(result["passed"])
        self.assertIn("truncated_capture", {f["code"] for f in result["failures"]})

    def test_malformed_identity_produces_report_instead_of_crashing(self):
        data = fixture_metadata()
        data.update(layer=["Temperature"], fontScale={"value": 1})
        (self.root / "bad.capture.json").write_text(json.dumps(data))
        report = continuity.analyze_root(self.root, self.root / "report.json")
        self.assertFalse(report["passed"])
        self.assertTrue((self.root / "report.json").is_file())

    def test_missing_corrupt_and_escaping_evidence_fail_closed(self):
        for video in ("missing.mp4", "corrupt.mp4", "../outside.mp4"):
            (self.root / "corrupt.mp4").write_bytes(b"not a video")
            data = fixture_metadata()
            data["video"] = video
            sidecar = self.root / "bad.capture.json"
            sidecar.write_text(json.dumps(data))
            result = continuity.analyze_capture(sidecar, self.root / "artifacts")
            self.assertFalse(result["passed"])
            self.assertIn("unreadable_evidence", {f["code"] for f in result["failures"]})


if __name__ == "__main__":
    unittest.main()
