#!/usr/bin/env python3
"""Fail closed on missing/crashed/failed Android tests; emit reviewable JSON/JUnit."""
import argparse
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET

# These five tests deliberately require separate radio/display/process phases, which
# run in the same required device lane after the normal suite. No other skip is OK.
PHASE_ONLY = {
    'zone.disinfo.wx.OfflinePreviewSeedTest#seedForMinifiedPreview',
    'zone.disinfo.wx.ScreenBeautyE2eTest#actualDeviceLargeTextLightDialogs',
    'zone.disinfo.wx.ScreenBeautyE2eTest#actualDeviceLargeTextDarkDialogs',
    'zone.disinfo.wx.ui.RadarOfflineIntegrationTest#seedColdProcessRadar',
    'zone.disinfo.wx.ui.RadarOfflineIntegrationTest#coldProcessRadarRestoresActualImageWithoutFrameMetadata',
}

def source_inventory(root):
    expected = set()
    for path in Path(root).rglob('*.kt'):
        source = path.read_text()
        count = len(re.findall(r'@Test\b', source))
        if not count:
            continue
        methods = re.findall(r'@Test(?:\([^)]*\))?\s+fun\s+(\w+)\s*\(', source)
        assert len(methods) == count, f'Unsupported test declaration in {path}; update source inventory parser'
        package = re.search(r'^package\s+([\w.]+)', source, re.M).group(1)
        assert re.search(r'^class\s+' + re.escape(path.stem) + r'\b', source, re.M), path
        for method in methods:
            key = package + '.' + path.stem + '#' + method
            assert key not in expected, f'Duplicate source test: {key}'
            expected.add(key)
    assert expected, 'No source @Test declarations found'
    return expected


def parse(text):
    fields, results = {}, {}
    for line in text.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, _, value = line[len('INSTRUMENTATION_STATUS: '):].partition('=')
            fields[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code = int(line.split(':', 1)[1])
            if code != 1 and 'class' in fields and 'test' in fields:
                key = fields['class'] + '#' + fields['test']
                if key in results:
                    raise AssertionError(f'Duplicate terminal result: {key}')
                results[key] = {'code': code, 'stack': fields.get('stack', '')}
            fields = {}
    return results


def validate(actual, inventory, allow_phases=False):
    assert inventory, 'Test discovery returned no tests'
    assert set(actual) == set(inventory), f'Test coverage differs: missing={set(inventory)-set(actual)} unexpected={set(actual)-set(inventory)}'
    bad = {key: value for key, value in actual.items()
           if value['code'] != 0 and not (allow_phases and key in PHASE_ONLY and value['code'] in (-3, -4))}
    assert not bad, f'Failed or unexpectedly skipped tests: {bad}'
    if allow_phases:
        assert PHASE_ONLY <= set(actual), 'Separate-phase test inventory changed; update the explicit coverage contract'


def main():
    p = argparse.ArgumentParser()
    p.add_argument('log', type=Path)
    p.add_argument('inventory', type=Path)
    p.add_argument('output', type=Path)
    p.add_argument('--allow-separate-phases', action='store_true')
    p.add_argument('--source-root', type=Path, default=Path('app/src/androidTest'))
    p.add_argument('--classes', default='')
    args = p.parse_args()
    raw = args.log.read_text()
    actual, inventory = parse(raw), parse(args.inventory.read_text())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({'discovered': len(inventory), 'executed': len(actual), 'tests': actual}, indent=2))
    suite = ET.Element('testsuite', name=args.output.stem, tests=str(len(actual)))
    for key, value in actual.items():
        classname, name = key.split('#', 1)
        case = ET.SubElement(suite, 'testcase', classname=classname, name=name)
        if value['code'] in (-3, -4):
            ET.SubElement(case, 'skipped', message='Executed in a separate required radio/display/process phase')
        elif value['code'] != 0:
            ET.SubElement(case, 'failure', message=value['stack'])
    ET.ElementTree(suite).write(args.output.with_suffix('.xml'), encoding='utf-8', xml_declaration=True)
    assert re.search(r'^OK \(\d+ tests?\)', raw, re.M), 'Missing successful instrumentation summary (crash or timeout)'
    expected = source_inventory(args.source_root)
    if args.classes:
        classes = set(args.classes.split(','))
        expected = {key for key in expected if key.split('#')[0] in classes}
    assert set(inventory) == expected, f'APK discovery differs from source: missing={expected-set(inventory)}, unexpected={set(inventory)-expected}'
    validate(actual, inventory, args.allow_separate_phases)
    print(f'Verified {len(actual)} / {len(inventory)} discovered tests; {sum(v["code"] == 0 for v in actual.values())} passed')

if __name__ == '__main__':
    main()
