#!/usr/bin/env python3
"""Aggregate the required matrix; missing/duplicate cases cannot produce a green gate."""
import json
import importlib.util
from pathlib import Path
import sys

spec = importlib.util.spec_from_file_location('phase_contract', Path(__file__).with_name('check-phase-tests.py'))
phases_contract = importlib.util.module_from_spec(spec)
spec.loader.exec_module(phases_contract)
spec = importlib.util.spec_from_file_location('continuity_gate', Path(__file__).with_name('check-radar-continuity-gate.py'))
continuity_gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(continuity_gate)

LAYERS = ['Temperature', 'Dew point', 'Wind', 'Wind gusts', 'Clouds',
          'Precip total', 'Snow total', 'Radar', 'Satellite', 'Radar + satellite']
LANES = ['debug', 'playback', 'layers-0', 'layers-1', 'layers-2']
EXPECTED = {(layer, index) for layer in LAYERS
            for index in range(2 if layer in ('Precip total', 'Snow total') else 3)}


def unique(root, name):
    files = list(root.rglob(name))
    assert len(files) == 1, f'Expected exactly one {name}; found {files}'
    return files[0]


def validate(root, native_video=False):
    for lane in LANES:
        assert unique(root, lane + '-status.txt').read_text().strip() == '0', f'{lane} failed'
        phases = unique(root, lane + '-phases.tsv').read_text().splitlines()
        assert len(phases) == (5 if lane == 'debug' else 1), f'{lane}: missing required phase'
        assert all(line.split('\t')[1] == '0' for line in phases), f'{lane}: failed phase'
        phases_contract.validate_results(lane, json.loads(unique(root, lane + '-phase-tests.json').read_text()), native_video)
    continuity_seen, acquisition_limited = set(), []
    for shard in range(3):
        declared = unique(root, f'layers-{shard}-native-video.txt').read_text().strip()
        assert phases_contract.native_video_enabled(declared) == native_video, 'Native video opt-in differs from requested workflow coverage'
        if not native_video:
            continue
        proof = json.loads(unique(root, f'layers-{shard}-continuity.json').read_text())
        statuses = [int(unique(root, f'layers-{shard}-continuity{suffix}-status.txt').read_text().strip())
                    for suffix in ('-acquisition', '-analysis', '')]
        gate = continuity_gate.validate(proof, *statuses, 3, shard)
        acquisition_limited.extend(gate['nonblockingAcquisitionLimits'])
        for capture in proof['captures']:
            key = (capture['layer'], float(capture['fontScale']))
            assert key not in continuity_seen, f'Duplicate continuous video evidence: {key}'
            assert LAYERS.index(key[0]) % 3 == shard, f'Wrong continuity shard: {key}'
            continuity_seen.add(key)
    if native_video:
        assert continuity_seen == {(layer, font) for layer in LAYERS for font in (1.0, 2.0)}, 'Missing native sequential layer/font video evidence'
    counts = {}
    for state in ('live', 'saved', 'empty'):
        seen, stress = set(), 0
        for shard in range(3):
            proof = json.loads(unique(root, f'layers-{shard}-{state}.json').read_text())
            assert proof['shardIndex'] == shard and proof['shardCount'] == 3
            assert proof['minifiedPreview'] is True and not proof['failures']
            for case in proof['results']:
                assert case['result'] == 'passed', case
                if 'rapidSwitches' in case:
                    assert state == 'live' and shard == 0
                    assert case['rapidSwitches'] == 20 and case['coldRestart'] is True
                    stress += 1
                else:
                    key = (case['layer'], case['rangeIndex'])
                    assert key not in seen, f'{state}: duplicate case {key}'
                    assert LAYERS.index(case['layer']) % 3 == shard, f'{state}: wrong shard {key}'
                    assert case.get('offline', False) == (state != 'live'), case
                    if state == 'live':
                        assert 'playback' in case, 'Missing native frame-advancement proof'
                    seen.add(key)
        assert seen == EXPECTED, f'{state}: missing={EXPECTED-seen}, unexpected={seen-EXPECTED}'
        assert stress == (1 if state == 'live' else 0), f'{state}: missing/duplicate cross-layer stress'
        counts[state] = len(seen)
    debug = json.loads(unique(root, 'debug-tests.json').read_text())
    assert debug['discovered'] == debug['executed'] == len(debug['tests'])
    expected_tests = phases_contract.instrumentation.source_inventory(Path(__file__).resolve().parents[2] / 'app/src/androidTest')
    assert set(debug['tests']) == expected_tests, 'Android result IDs differ from source @Test declarations'
    phases_contract.instrumentation.validate(debug['tests'], {key: {} for key in expected_tests}, allow_phases=True)
    return {'continuous_native_videos': len(continuity_seen),
            'native_video_evidence_requested': native_video,
            'native_video_status': ('acquisition-limited' if acquisition_limited else 'passed') if native_video else 'not-requested',
            'continuity_proven': native_video and not acquisition_limited,
            'nonblocking_acquisition_limits': acquisition_limited,
            'layers': counts, 'android_tests': debug['executed'], 'required_lanes': LANES}

if __name__ == '__main__':
    print(json.dumps(validate(Path(sys.argv[1]), phases_contract.native_video_enabled()), indent=2))
