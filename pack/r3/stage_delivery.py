#!/usr/bin/env python3
"""Stage the tested R3 tuple, enabled configuration and layered content without installing it."""
import hashlib,json,pathlib,shutil,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[2]
OUT=ROOT/'build/r3-delivery'
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def main():
    if OUT.exists() or OUT.with_suffix('.zip').exists():raise SystemExit('Refusing to overwrite an existing R3 delivery')
    validation=json.loads((ROOT/'build/r3-validation.json').read_text())
    if validation.get('status')!='passed':raise SystemExit('R3 validation is incomplete')
    artifacts=[ROOT/path for path in validation['artifacts']]
    for artifact in artifacts:
        if not artifact.is_file() or digest(artifact)!=validation['artifacts'][str(artifact.relative_to(ROOT))]:raise SystemExit(f'Untested artifact: {artifact}')
    OUT.mkdir(parents=True);(OUT/'mods').mkdir();(OUT/'api').mkdir()
    for artifact in artifacts:
        shutil.copy2(artifact,OUT/'mods'/artifact.name)
        for api in artifact.parent.glob('*-api.jar'):shutil.copy2(api,OUT/'api'/api.name)
    for release in ['r1','r2','r3']:
        shutil.copytree(ROOT/f'pack/{release}/kubejs',OUT/'kubejs',dirs_exist_ok=True)
        shutil.copytree(ROOT/f'pack/{release}/providers',OUT/f'provider-sources/{release}',ignore=shutil.ignore_patterns('__pycache__'))
    shutil.copytree(ROOT/'pack/r3/config',OUT/'config')
    shutil.copy2(ROOT/'docs/R3-Native-Control.md',OUT/'R3-Implementation.md')
    shutil.copy2(ROOT/'build/r3-validation.json',OUT/'validation.json')
    for relative in validation.get('evidence',[]):
        source=ROOT/relative;target=OUT/'evidence'/relative;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
    (OUT/'README.md').write_text('''# R3 warfare, territory and world integration

Staged delivery. No live instance has been changed.

Replace the matching runtime jars on both server and clients, removing older duplicates. Merge kubejs and config into the pack. Every R3 feature defaults enabled in the included configuration. MCA Quests protocol 17 requires matching client/server builds. Recruits 1.15.2 is the audited military provider; its original jar is required and is not redistributed here. Existing MCA/Townstead and optional provider dependencies remain required by their mods.

See R3-Implementation.md for explicit faction/claim setup, mandates, commands, civilian contracts and saved recovery. No existing player or NPC is automatically enrolled into a military faction. The api directory contains development artifacts, not additional runtime mods. Provider source exports layer on R1 and R2; their hash manifests identify supported baselines. validation.json identifies the tested jars and evidence. No world or player data is included.
''')
    manifest={str(p.relative_to(OUT)):digest(p) for p in sorted(OUT.rglob('*')) if p.is_file()}
    (OUT/'sha256.json').write_text(json.dumps(manifest,indent=2)+'\n')
    with zipfile.ZipFile(OUT.with_suffix('.zip'),'w',zipfile.ZIP_DEFLATED) as archive:
        for p in sorted(OUT.rglob('*')):
            if p.is_file():archive.write(p,str(p.relative_to(OUT)))
    print(f'Staged {len(manifest)} verified files: {OUT.with_suffix(".zip")}')
if __name__=='__main__':main()
