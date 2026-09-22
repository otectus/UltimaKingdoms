#!/usr/bin/env python3
"""Stage the validated R2 tuple and layered R1/R2 source/content without touching an instance."""
import hashlib,json,pathlib,shutil,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[2]
OUT=ROOT/'build/r2-delivery'
def props(root):
    return dict(line.strip().split('=',1) for line in (root/'gradle.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
def jar(root):
    p=props(root)
    return root/'build/libs'/f"{p['mod_id']}-{p['mod_version']}.jar"
def main():
    if OUT.exists() or OUT.with_suffix('.zip').exists():raise SystemExit('Refusing to overwrite an existing R2 delivery')
    roots=[ROOT,ROOT/'build/r2-provider-work/MCAQuests',ROOT/'build/r2-provider-work/MCACrime',ROOT/'build/r1-provider-work/MCAConversations']
    artifacts=[jar(root) for root in roots]
    validation=json.loads((ROOT/'build/r2-validation.json').read_text())
    if validation.get('status')!='passed':raise SystemExit('Final R2 validation is incomplete')
    for artifact in artifacts:
        if not artifact.is_file():raise SystemExit(f'Missing artifact: {artifact}')
        expected=validation['artifacts'].get(str(artifact.relative_to(ROOT)))
        if expected!=hashlib.sha256(artifact.read_bytes()).hexdigest():raise SystemExit(f'Artifact differs from validation: {artifact}')
    OUT.mkdir(parents=True);(OUT/'mods').mkdir();(OUT/'api').mkdir()
    for artifact in artifacts:shutil.copy2(artifact,OUT/'mods'/artifact.name)
    for root in roots:
        for artifact in (root/'build/libs').glob('*-api.jar'):shutil.copy2(artifact,OUT/'api'/artifact.name)
    shutil.copytree(ROOT/'pack/r1/kubejs',OUT/'kubejs')
    shutil.copytree(ROOT/'pack/r2/kubejs',OUT/'kubejs',dirs_exist_ok=True)
    for release in ['r1','r2']:
        shutil.copytree(ROOT/f'pack/{release}/providers',OUT/f'provider-sources/{release}',ignore=shutil.ignore_patterns('__pycache__'))
    shutil.copy2(ROOT/'docs/R2-Institutions-and-Agreements.md',OUT/'R2-Implementation.md')
    shutil.copy2(ROOT/'docs/R1-Civic-Foundation.md',OUT/'R1-Implementation.md')
    shutil.copy2(ROOT/'build/r2-validation.json',OUT/'validation.json')
    for relative in validation.get('evidence',[]):
        source=ROOT/relative
        target=OUT/'evidence'/relative
        target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(source,target)

    (OUT/'README.md').write_text('''# R2 institutions and agreements

Staged delivery; no live installation has been performed.

Back up the complete world, playerdata and instance together. Replace the matching four runtime jars in mods on both clients and server; remove duplicate older versions. Merge kubejs into the existing pack. The overlay contains the twelve R1 adaptations and the new once-only paid workshop commission. Restart with matching jars.

R2 institutional services default to enabled. Existing explicit false values for regional_civic_network.institutionalServices in ultima-kingdoms-civic-common.toml remain respected; change those to true to enable R2. Enable Crime's Townstead and service-restriction options for paid workshop service. Existing civic, membership and provider-owned gameplay retains its own configuration. See R2-Implementation.md for institutional recognition, qualifications, native restitution, signed hospitality, recovery and persistence boundaries.

api contains development artifacts, not additional runtime mods. provider-sources contains the R1 baseline changes and layered R2 source deltas/hash manifests. Apply only against matching baselines; these exports do not modify sibling checkouts automatically. validation.json records the exact tested artifacts and remaining limits. No world data is included.
''')
    manifest={str(p.relative_to(OUT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(OUT.rglob('*')) if p.is_file()}
    (OUT/'sha256.json').write_text(json.dumps(manifest,indent=2)+'\n')
    with zipfile.ZipFile(OUT.with_suffix('.zip'),'w',zipfile.ZIP_DEFLATED) as bundle:
        for p in sorted(OUT.rglob('*')):
            if p.is_file():bundle.write(p,str(p.relative_to(OUT)))
    print(f'Staged {len(manifest)} verified files: {OUT.with_suffix(".zip")}')
if __name__=='__main__':main()
