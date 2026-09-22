#!/usr/bin/env python3
"""Apply reviewed R1 companion source files only after verifying every baseline hash.

No builds, jar installations, live pack changes or git operations are performed.
"""
import argparse,datetime,hashlib,json,pathlib,shutil
ROOT=pathlib.Path(__file__).resolve().parent
TARGETS={'MCAQuests':pathlib.Path('/home/otectus/Projects/MCAQuests'),'MCAConversations':pathlib.Path('/home/otectus/Projects/MCAConversations')}

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--apply',action='store_true');args=parser.parse_args();changes=[]
    for name,target in TARGETS.items():
        manifest=json.loads((ROOT/name/'changes.json').read_text())
        for entry in manifest:
            relative=pathlib.PurePosixPath(entry['path'])
            if relative.is_absolute() or '..' in relative.parts or any(part in {'.git','build','.gradle','run'} for part in relative.parts):raise SystemExit(f'Unsafe source path: {relative}')
            source=ROOT/name/'files'/relative;destination=target/relative
            if not destination.resolve().is_relative_to(target.resolve()):raise SystemExit(f'Source path escapes provider checkout: {destination}')
            if digest(source)!=entry['after_sha256']:raise SystemExit(f'Artifact changed: {source}')
            current=digest(destination)
            if current==entry['after_sha256']:continue
            if current!=entry['before_sha256']:raise SystemExit(f'Existing work changed since baseline: {destination}')
            changes.append((name,relative,source,destination))
    print(f'Verified {len(changes)} source changes across {len(TARGETS)} companion repositories.')
    if not args.apply:return
    backup=pathlib.Path('/tmp')/('ultima-r1-provider-source-backup-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'));backup.mkdir()
    for name,relative,source,destination in changes:
        if destination.is_file():
            saved=backup/name/relative;saved.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(destination,saved)
        destination.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,destination)
    (backup/'applied.json').write_text(json.dumps([{'provider':n,'path':str(r),'sha256':digest(d)} for n,r,s,d in changes],indent=2))
    print(f'Applied tested R1 source changes; prior source backup: {backup}')
if __name__=='__main__':main()
