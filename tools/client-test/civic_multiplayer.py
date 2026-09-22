#!/usr/bin/env python3
"""Two isolated real clients exercising GuildScreen over the packaged civic network."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import socket
import subprocess
import time
import uuid

from production_client import allowed

ROOT = pathlib.Path(__file__).resolve().parents[2]
INSTALL = pathlib.Path('/home/otectus/Documents/curseforge/minecraft/Install')
DEFAULT_XVFB = pathlib.Path('/tmp/jewelcraft-xvfb/usr/bin/Xvfb')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--work-dir', type=pathlib.Path, required=True)
    parser.add_argument('--xvfb', type=pathlib.Path, default=DEFAULT_XVFB)
    parser.add_argument('--display', default=':97')
    args = parser.parse_args()
    work = args.work_dir.resolve()
    if work.exists():
        raise SystemExit(f'Refusing to reuse {work}')
    if not args.xvfb.is_file():
        raise SystemExit(f'Headless X server not found: {args.xvfb}')

    work.mkdir(parents=True)
    server = work / 'server'
    server.mkdir()
    (server / 'mods').mkdir()
    libraries = ROOT / 'build/forge-server/libraries'
    if not libraries.is_dir():
        raise SystemExit(f'Packaged Forge server libraries are missing: {libraries}')
    (server / 'libraries').symlink_to(libraries, target_is_directory=True)

    mod = ROOT / 'build/libs/ultima_kingdoms-0.1.0.jar'
    harness = ROOT / 'build/test-artifacts/ultima_kingdoms-0.1.0-client-acceptance.jar'
    for artifact in (mod, harness):
        if not artifact.is_file():
            raise SystemExit(f'Missing packaged artifact: {artifact}')
    shutil.copy2(mod, server / 'mods' / mod.name)
    (server / 'eula.txt').write_text('eula=true\n')
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    (server / 'server.properties').write_text(
        'online-mode=false\n'
        'enforce-secure-profile=false\n'
        'server-ip=127.0.0.1\n'
        f'server-port={port}\n'
        'level-type=minecraft:flat\n'
        'generate-structures=false\n'
        'difficulty=peaceful\n'
        'spawn-monsters=false\n'
        'view-distance=2\n'
        'simulation-distance=2\n'
        'spawn-protection=0\n')

    vanilla = json.loads((INSTALL / 'versions/1.20.1/1.20.1.json').read_text())
    forge = json.loads((INSTALL / 'versions/forge-47.4.23/forge-47.4.23.json').read_text())
    resolved = {}
    for library in vanilla['libraries'] + forge['libraries']:
        if allowed(library.get('rules')):
            parts = library['name'].split(':')
            resolved[':'.join(parts[:2]) + (':' + parts[3] if len(parts) > 3 else '')] = library
    classpath = [str(INSTALL / 'libraries' / library['downloads']['artifact']['path'])
                 for library in resolved.values() if library.get('downloads', {}).get('artifact')]
    classpath.append(str(INSTALL / 'versions/1.20.1/1.20.1.jar'))

    processes = []
    logs = []
    environment = os.environ.copy()
    environment.update({'DISPLAY': args.display, 'LIBGL_ALWAYS_SOFTWARE': '1'})

    def spawn(command, cwd, log_path, env=environment):
        output = log_path.open('w')
        logs.append(output)
        process = subprocess.Popen(command, cwd=cwd, stdin=subprocess.PIPE, stdout=output,
                                   stderr=output, text=True, env=env)
        processes.append(process)
        return process

    def wait_until(predicate, seconds=180):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            failures = list(work.glob('*-FAIL.txt'))
            if failures:
                raise RuntimeError('Client failure: ' + ', '.join(str(path) for path in failures))
            if predicate():
                return
            failed = [process for process in processes if process.poll() not in (None, 0)]
            if failed:
                raise RuntimeError('Runtime exited with an error; inspect logs under ' + str(work))
            time.sleep(.25)
        raise RuntimeError('Timed out; inspect ' + str(work))

    try:
        xvfb = spawn([str(args.xvfb), args.display, '-screen', '0', '1920x1080x24',
                      '-nolisten', 'tcp', '-ac'], work, work / 'xvfb.log')
        display_number = args.display.removeprefix(':').split('.')[0]
        wait_until(lambda: pathlib.Path('/tmp/.X11-unix', 'X' + display_number).exists(), 30)

        server_log = server / 'console.log'
        dedicated = spawn(['java', '-Xmx2G',
                           '@libraries/net/minecraftforge/forge/1.20.1-47.4.23/unix_args.txt', 'nogui'],
                          server, server_log)
        wait_until(lambda: 'Done (' in server_log.read_text(errors='replace'))

        for role, name in [('leader', 'CivicLeader'), ('peer', 'CivicPeer')]:
            folder = work / role
            folder.mkdir()
            (folder / 'mods').mkdir()
            (folder / 'natives').mkdir()
            for artifact in (mod, harness):
                shutil.copy2(artifact, folder / 'mods' / artifact.name)
            values = {
                'auth_player_name': name,
                'version_name': 'forge-47.4.23',
                'game_directory': str(folder),
                'assets_root': str(INSTALL / 'assets'),
                'assets_index_name': vanilla['assetIndex']['id'],
                'auth_uuid': str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + name).encode()).digest(),
                                           version=3)).replace('-', ''),
                'auth_access_token': '0', 'clientid': '', 'auth_xuid': '', 'user_type': 'legacy',
                'version_type': 'release', 'natives_directory': str(folder / 'natives'),
                'launcher_name': 'UltimaAcceptance', 'launcher_version': '1',
                'classpath': ':'.join(classpath), 'classpath_separator': ':',
                'library_directory': str(INSTALL / 'libraries'),
            }

            def expand(arguments):
                result = []
                for argument in arguments:
                    if isinstance(argument, dict):
                        if not allowed(argument.get('rules')):
                            continue
                        argument = argument['value']
                    for value in argument if isinstance(argument, list) else [argument]:
                        value = re.sub(r'\$\{([^}]+)\}', lambda match: values[match.group(1)], value)
                        if value.startswith('-DignoreList='):
                            value += ',1.20.1.jar'
                        result.append(value)
                return result

            command = (['java', '-Xmx2G', f'-Dultima.clientTest.output={folder}',
                        '-Dultima.clientTest.civicMultiplayer=true',
                        f'-Dultima.clientTest.shared={work}', f'-Dultima.clientTest.role={role}',
                        f'-Dultima.clientTest.server=127.0.0.1:{port}']
                       + expand(vanilla['arguments']['jvm'] + forge['arguments']['jvm'])
                       + [forge['mainClass']]
                       + expand(vanilla['arguments']['game'] + forge['arguments']['game'])
                       + ['--width', '960', '--height', '640'])
            (folder / 'launch-command.json').write_text(json.dumps(command, indent=2))
            spawn(command, folder, folder / 'console.log')

        wait_until(lambda: all(name + ' logged in' in server_log.read_text(errors='replace')
                               or re.search(name + r'\[.*logged in', server_log.read_text(errors='replace'))
                               for name in ('CivicLeader', 'CivicPeer')))
        (work / 'READY').write_text('ready\n')
        wait_until(lambda: (work / 'leader-PASS.txt').exists() and (work / 'peer-PASS.txt').exists(), 300)
        for process in processes[2:]:
            if process.wait(timeout=60) != 0:
                raise RuntimeError('Client exited with an error')
        screenshots = sorted(work.glob('*/screenshots/*-guild-*.png'))
        if len(screenshots) != 4:
            raise RuntimeError(f'Expected four GuildScreen screenshots, found {screenshots}')
        dedicated.stdin.write('stop\n')
        dedicated.stdin.flush()
        if dedicated.wait(timeout=60) != 0:
            raise RuntimeError('Dedicated server exited with an error')
        (work / 'artifacts.sha256').write_text(''.join(
            hashlib.sha256(artifact.read_bytes()).hexdigest() + '  ' + artifact.name + '\n'
            for artifact in (mod, harness)))
        print((work / 'leader-PASS.txt').read_text().strip())
        print((work / 'peer-PASS.txt').read_text().strip())
        print('Artifacts: ' + str(work))
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
        for output in logs:
            output.close()


if __name__ == '__main__':
    main()
