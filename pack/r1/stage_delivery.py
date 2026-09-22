#!/usr/bin/env python3
"""Stage the tested R1 jars, pack overlay, source patches and instructions without touching a live instance."""
import hashlib,json,pathlib,shutil,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[2]
OUT=ROOT/'build/r1-delivery'
ARTIFACTS=[ROOT/'build/libs/ultima_kingdoms-0.1.0.jar',ROOT/'build/r1-provider-work/MCAQuests/build/libs/mcaquests-1.6.6.jar',ROOT/'build/r1-provider-work/MCAConversations/build/libs/mcaconversations-1.7.2.jar']
def main():
    if OUT.exists():raise SystemExit(f'Refusing to overwrite existing delivery: {OUT}')
    for artifact in ARTIFACTS:
        if not artifact.is_file():raise SystemExit(f'Missing tested artifact: {artifact}')
    OUT.mkdir(parents=True);(OUT/'mods').mkdir();(OUT/'api').mkdir()
    for artifact in ARTIFACTS:shutil.copy2(artifact,OUT/'mods'/artifact.name)
    for artifact in (ROOT/'build/libs').glob('*-api.jar'):shutil.copy2(artifact,OUT/'api'/artifact.name)
    for artifact in (ROOT/'build/r1-provider-work/MCAQuests/build/libs').glob('*-api.jar'):shutil.copy2(artifact,OUT/'api'/artifact.name)
    shutil.copytree(ROOT/'pack/r1/kubejs',OUT/'kubejs')
    shutil.copytree(ROOT/'pack/r1/providers',OUT/'provider-sources',ignore=shutil.ignore_patterns('__pycache__'))
    shutil.copy2(ROOT/'pack/r1/quest-manifest.json',OUT/'quest-manifest.json')
    shutil.copy2(ROOT/'docs/R1-Civic-Foundation.md',OUT/'R1-Implementation.md')
    shutil.copy2(ROOT/'build/r1-completion-validation.json',OUT/'validation.json')
    (OUT/'README.md').write_text('''# R1 regional civic network delivery

This is a staged delivery, not a live installation.

Back up the complete instance/world/playerdata together. Replace the matching three jars in `mods/`; do not keep duplicate older copies. Merge `kubejs/` into the existing pack, preserving other files. The twelve overlays keep quest IDs, rewards, objectives and prerequisites; source hashes are in `quest-manifest.json`. Fully restart the game/server after installing provider jars. Use matching jars on the server and clients.

`api/` contains development artifacts, not additional runtime mods. `provider-sources/` contains reviewed source deltas and baseline hashes. See `R1-Implementation.md` for configuration, durability boundaries, commands, migration and known capacity limits. No world files are included.
''')
    manifest={str(p.relative_to(OUT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(OUT.rglob('*')) if p.is_file()}
    (OUT/'sha256.json').write_text(json.dumps(manifest,indent=2)+'\n')
    archive=OUT.with_suffix('.zip')
    if archive.exists():raise SystemExit(f'Refusing to overwrite {archive}')
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as bundle:
        for p in sorted(OUT.rglob('*')):
            if p.is_file():bundle.write(p,str(p.relative_to(OUT)))
    print(f'Staged {len(manifest)} files: {archive}')
if __name__=='__main__':main()
