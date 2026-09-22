#!/usr/bin/env python3
"""Launch the isolated packaged Kingdom Actions client acceptance."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import time
import uuid

from production_client import allowed

ROOT = pathlib.Path(__file__).resolve().parents[2]
INSTALL = pathlib.Path('/home/otectus/Documents/curseforge/minecraft/Install')
DEFAULT_MOD = ROOT / 'build/libs/ultima_kingdoms-0.1.0.jar'
DEFAULT_HARNESS = ROOT / 'build/test-artifacts/ultima_kingdoms-0.1.0-client-acceptance.jar'
DEFAULT_XVFB = pathlib.Path('/tmp/jewelcraft-xvfb/usr/bin/Xvfb')
SCREENSHOTS = [
    '01-create-review.png',
    '02-created-read.png',
    '03-rename-result.png',
    '04-compact-tasks.png',
    '05-war-room-wizard.png',
]


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def expand(arguments, values):
    result = []
    for argument in arguments:
        if isinstance(argument, dict):
            if not allowed(argument.get('rules')):
                continue
            argument = argument['value']
        for value in argument if isinstance(argument, list) else [argument]:
            value = re.sub(r'\$\{([^}]+)\}', lambda match: values[match[1]], value)
            if value.startswith('-DignoreList='):
                value += ',1.20.1.jar'
            result.append(value)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work-dir', required=True, type=pathlib.Path)
    parser.add_argument('--suite', choices=['interactions', 'politics'], default='interactions')
    parser.add_argument('--mod', type=pathlib.Path, default=DEFAULT_MOD)
    parser.add_argument('--harness', type=pathlib.Path, default=DEFAULT_HARNESS)
    parser.add_argument('--xvfb', type=pathlib.Path, default=DEFAULT_XVFB)
    parser.add_argument('--display', default=':95')
    args = parser.parse_args()

    work = args.work_dir.resolve()
    mod = args.mod.resolve()
    harness = args.harness.resolve()
    xvfb = args.xvfb.resolve()
    if work.exists():
        raise SystemExit(f'Refusing to reuse {work}')
    for artifact in (mod, harness, xvfb):
        if not artifact.is_file():
            raise SystemExit(f'Missing required file {artifact}')

    work.mkdir(parents=True)
    (work / 'mods').mkdir()
    (work / 'natives').mkdir()
    copied = []
    for artifact in (mod, harness):
        target = work / 'mods' / artifact.name
        shutil.copy2(artifact, target)
        copied.append(target)
    (work / 'artifacts.json').write_text(json.dumps({p.name: sha256(p) for p in copied}, indent=2) + '\n')
    # Suppress the first-install accessibility screen while the Java harness still handles it defensively.
    (work / 'options.txt').write_text('onboardAccessibility:false\npauseOnLostFocus:false\nguiScale:2\n')

    vanilla = json.loads((INSTALL / 'versions/1.20.1/1.20.1.json').read_text())
    forge = json.loads((INSTALL / 'versions/forge-47.4.23/forge-47.4.23.json').read_text())
    libraries = {}
    for library in vanilla['libraries'] + forge['libraries']:
        if allowed(library.get('rules')):
            pieces = library['name'].split(':')
            key = ':'.join(pieces[:2]) + (':' + pieces[3] if len(pieces) > 3 else '')
            libraries[key] = library
    classpath = []
    for library in libraries.values():
        artifact = library.get('downloads', {}).get('artifact')
        if artifact:
            path = INSTALL / 'libraries' / artifact['path']
            if not path.is_file():
                raise SystemExit(f'Missing launcher library {path}')
            classpath.append(str(path))
    classpath.append(str(INSTALL / 'versions/1.20.1/1.20.1.jar'))

    values = {
        'auth_player_name': 'InteractionTest',
        'version_name': 'forge-47.4.23',
        'game_directory': str(work),
        'assets_root': str(INSTALL / 'assets'),
        'assets_index_name': vanilla['assetIndex']['id'],
        'auth_uuid': uuid.uuid3(uuid.NAMESPACE_DNS, 'OfflinePlayer:InteractionTest').hex,
        'auth_access_token': '0', 'clientid': '', 'auth_xuid': '', 'user_type': 'legacy',
        'version_type': 'release', 'natives_directory': str(work / 'natives'),
        'launcher_name': 'UltimaAcceptance', 'launcher_version': '1',
        'classpath': ':'.join(classpath), 'classpath_separator': ':',
        'library_directory': str(INSTALL / 'libraries'),
    }
    command = [
        'java', '-Xmx4G', f'-Dultima.clientTest.output={work}', f'-Dultima.clientTest.{args.suite}=true',
        *expand(vanilla['arguments']['jvm'] + forge['arguments']['jvm'], values), forge['mainClass'],
        *expand(vanilla['arguments']['game'] + forge['arguments']['game'], values),
        '--width', '1280', '--height', '800',
    ]
    (work / 'launch-command.json').write_text(json.dumps(command, indent=2) + '\n')
    environment = os.environ.copy()
    environment.update(DISPLAY=args.display, LIBGL_ALWAYS_SOFTWARE='1')
    completed = None
    with (work / 'xvfb.log').open('w') as display_log, (work / 'client.log').open('w') as client_log:
        display = subprocess.Popen([str(xvfb), args.display, '-screen', '0', '1600x1000x24', '-nolisten', 'tcp', '-ac'], stdout=display_log, stderr=display_log)
        try:
            time.sleep(1)
            completed = subprocess.run(command, cwd=work, env=environment, stdout=client_log, stderr=subprocess.STDOUT, timeout=480)
        finally:
            display.terminate()
            display.wait(timeout=15)

    marker = work / ('INTERACTION_PASS.txt' if args.suite == 'interactions' else 'POLITICS_PASS.txt')
    failure = work / ('INTERACTION_FAIL.txt' if args.suite == 'interactions' else 'FAIL.txt')
    if completed.returncode != 0 or not marker.is_file():
        detail = failure.read_text() if failure.is_file() else 'No harness failure marker was written.'
        raise SystemExit(f'Interaction client acceptance failed; inspect {work}/client.log\n{detail}')
    expected = SCREENSHOTS if args.suite == 'interactions' else ['politics-overview.png', 'politics-council.png', 'politics-form-compact.png', 'politics-petitions-compact.png']
    missing = [name for name in expected if not (work / 'screenshots' / name).is_file() or (work / 'screenshots' / name).stat().st_size == 0]
    if missing:
        raise SystemExit(f'Interaction acceptance omitted screenshots: {missing}')
    print(marker.read_text().strip())
    print(f'Artifact hashes and screenshots: {work}')


if __name__ == '__main__':
    main()
