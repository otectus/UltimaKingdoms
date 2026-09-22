#!/usr/bin/env python3
"""Check final release jars do not accidentally ship test fixtures or optional dependencies."""
import pathlib,zipfile,hashlib
root=pathlib.Path(__file__).resolve().parents[2]
properties=dict(line.strip().split('=',1) for line in (root/'gradle.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
for suffix in ('','-api','-sources'):
    path=root/'build/libs'/f"{properties['mod_id']}-{properties['mod_version']}{suffix}.jar"
    with zipfile.ZipFile(path) as jar:
        names=jar.namelist()
        assert not any('clienttest' in n or 'ultima_client_test' in n or 'GameTests' in n or 'acceptance/' in n for n in names),f'Test content leaked: {path}'
        optional=('forge/net/mca/','net/mca/','forge/net/conczin/mca/','net/conczin/mca/',
                  'dev/architectury/','com/talhanation/recruits/','dev/otectus/mcaquests/','dev/otectus/mcaconversations/',
                  'dev/otectus/mcareputation/','com/aetherianartificer/townstead/')
        assert not any(n.startswith(optional) for n in names),f'Optional dependency embedded: {path}'
        if suffix=='-api':
            classes=[n for n in names if n.endswith('.class')]
            assert classes and all(n.startswith('com/ultimakingdoms/api/') for n in classes)
            assert not any(n.endswith(('.json','.toml','.png')) for n in names)
            forbidden=tuple(s.encode() for s in (*optional,'net/minecraft/client/',
                            'com/ultimakingdoms/core/','com/ultimakingdoms/client/',
                            'com/ultimakingdoms/compat/','com/ultimakingdoms/factions/',
                            'com/ultimakingdoms/integration/'))
            assert all(not any(value in jar.read(n) for value in forbidden) for n in classes)
        elif suffix=='':
            assert 'META-INF/mods.toml' in names and 'assets/ultima_kingdoms/lang/en_us.json' in names
            assert not any(n.endswith('.java') for n in names)
        print(f'PASS {path.name}: {len(names)} entries; sha256 {hashlib.sha256(path.read_bytes()).hexdigest()}')
