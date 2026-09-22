#!/usr/bin/env python3
"""Read the current pack, replace only R1 artifacts, and open a copied old world in an isolated client."""
import argparse, hashlib, json, os, pathlib, re, shutil, subprocess, time, uuid
from production_client import allowed
ROOT=pathlib.Path(__file__).resolve().parents[2]
PACK=pathlib.Path('/home/otectus/Documents/curseforge/minecraft/Instances/Ultima')
INSTALL=pathlib.Path('/home/otectus/Documents/curseforge/minecraft/Install')

def hashes(folder):
    return {str(p.relative_to(folder)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(folder.rglob('*')) if p.is_file()}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--work-dir',required=True,type=pathlib.Path);parser.add_argument('--r2',action='store_true');parser.add_argument('--r3',action='store_true');parser.add_argument('--r4',action='store_true');parser.add_argument('--gui-style',action='store_true');parser.add_argument('--source-world',type=pathlib.Path,default=PACK/'saves/Test World 1');args=parser.parse_args()
    if args.r4:args.r3=True
    work=args.work_dir.resolve()
    if work.exists():raise SystemExit(f'Refusing to reuse {work}')
    work.mkdir(parents=True);(work/'natives').mkdir()
    source_hashes=hashes(args.source_world)
    for directory in ['mods','config','defaultconfigs','kubejs','resourcepacks','datapacks','global_packs','paxi']:
        if (PACK/directory).is_dir():shutil.copytree(PACK/directory,work/directory)
    for name in ['options.txt','optionsof.txt']:
        if (PACK/name).is_file():shutil.copy2(PACK/name,work/name)
    shutil.copytree(args.source_world,work/'saves/r1-old-world-copy')
    replacements=[ROOT/'build/libs/ultima_kingdoms-0.1.0.jar',ROOT/'build/r1-provider-work/MCAQuests/build/libs/mcaquests-1.6.6.jar',ROOT/'build/r1-provider-work/MCAConversations/build/libs/mcaconversations-1.7.2.jar',ROOT/'build/test-artifacts/ultima_kingdoms-0.1.0-client-acceptance.jar']
    if args.r2 or args.r3:
        replacements=[p for p in replacements if 'MCAQuests' not in str(p)]
        replacements.extend([ROOT/'build/r2-provider-work/MCAQuests/build/libs/mcaquests-1.6.6.jar',ROOT/'build/r2-provider-work/MCACrime/build/libs/mcacrime-0.7.5.jar'])
    if args.r3:
        replacements=[p for p in replacements if 'MCAQuests' not in str(p) and 'MCACrime' not in str(p)]
        replacements.extend([ROOT/'build/r3-provider-work/MCAQuests/build/libs/mcaquests-1.6.6.jar',ROOT/'build/r3-provider-work/MCACrime/build/libs/mcacrime-0.7.5.jar'])
    for jar in replacements:
        if not jar.is_file():raise SystemExit(f'Missing R1 artifact {jar}')
        shutil.copy2(jar,work/'mods'/jar.name)
    shutil.copytree(ROOT/'pack/r1/kubejs',work/'kubejs',dirs_exist_ok=True)
    if args.r2 or args.r3:
        shutil.copytree(ROOT/'pack/r2/kubejs',work/'kubejs',dirs_exist_ok=True)
    if args.r3:
        shutil.copytree(ROOT/'pack/r3/kubejs',work/'kubejs',dirs_exist_ok=True)
        shutil.copytree(ROOT/'pack/r3/config',work/'config',dirs_exist_ok=True)
        # This newly generated test identity is not a character-creation test. MCA's initial
        # Destiny screen pauses the integrated world and floods villager-preview requests.
        # Change only the isolated copy so the benchmark measures active world ticks.
        for config in (work/'config').glob('*mca*.json'):
            data=json.loads(config.read_text())
            if 'launchIntoDestiny' in data:
                data['launchIntoDestiny']=False
                config.write_text(json.dumps(data,indent=2)+'\n')
                (work/'fixture-overrides.json').write_text(json.dumps({'config':str(config.relative_to(work)),'launchIntoDestiny':False,'purpose':'skip first-login character creation in isolated benchmark; R3 switches remain enabled'},indent=2)+'\n')
    (work/'artifacts.json').write_text(json.dumps(hashes(work/'mods'),indent=2))
    (work/'source-world-before.json').write_text(json.dumps(source_hashes,indent=2))
    vanilla=json.loads((INSTALL/'versions/1.20.1/1.20.1.json').read_text());forge=json.loads((INSTALL/'versions/forge-47.4.23/forge-47.4.23.json').read_text())
    libraries={}
    for lib in vanilla['libraries']+forge['libraries']:
        if allowed(lib.get('rules')):libraries[':'.join(lib['name'].split(':')[:2]) + (':'+lib['name'].split(':')[3] if len(lib['name'].split(':'))>3 else '')]=lib
    cp=[str(INSTALL/'libraries'/lib['downloads']['artifact']['path']) for lib in libraries.values() if lib.get('downloads',{}).get('artifact')]+[str(INSTALL/'versions/1.20.1/1.20.1.jar')]
    values={'auth_player_name':'R1PackTest','version_name':'forge-47.4.23','game_directory':str(work),'assets_root':str(INSTALL/'assets'),'assets_index_name':vanilla['assetIndex']['id'],'auth_uuid':uuid.uuid3(uuid.NAMESPACE_DNS,'OfflinePlayer:R1PackTest').hex,'auth_access_token':'0','clientid':'','auth_xuid':'','user_type':'legacy','version_type':'release','natives_directory':str(work/'natives'),'launcher_name':'UltimaAcceptance','launcher_version':'1','classpath':':'.join(cp),'classpath_separator':':','library_directory':str(INSTALL/'libraries')}
    def expand(arguments):
        result=[]
        for arg in arguments:
            if isinstance(arg,dict):
                if not allowed(arg.get('rules')):continue
                arg=arg['value']
            for value in arg if isinstance(arg,list) else [arg]:
                value=re.sub(r'\$\{([^}]+)\}',lambda m:values[m[1]],value)
                if value.startswith('-DignoreList='):value+=',1.20.1.jar'
                result.append(value)
        return result
    command=['java','-Xmx8G',f'-Dultima.clientTest.output={work}','-Dultima.clientTest.r1Pack=true']+expand(vanilla['arguments']['jvm']+forge['arguments']['jvm'])+[forge['mainClass']]+expand(vanilla['arguments']['game']+forge['arguments']['game'])+['--width','1280','--height','800']
    if args.r2 or args.r3:command.insert(1,'-Dultima.clientTest.r2Pack=true')
    if args.r3:
        command.insert(1,'-Dultima.clientTest.r3Pack=true')
        if args.r4:command.insert(1,'-Dultima.clientTest.r4Pack=true')
        if args.gui_style:command.insert(1,'-Dultima.clientTest.guiStyle=true')
    (work/'launch-command.json').write_text(json.dumps(command,indent=2))
    environment=os.environ.copy();environment.update(DISPLAY=':98',LIBGL_ALWAYS_SOFTWARE='1')
    with (work/'xvfb.log').open('w') as xlog,(work/'client.log').open('w') as log:
        display=subprocess.Popen(['/tmp/jewelcraft-xvfb/usr/bin/Xvfb',':98','-screen','0','1600x1000x24','-nolisten','tcp','-ac'],stdout=xlog,stderr=xlog)
        try:
            time.sleep(1)
            completed=subprocess.run(command,cwd=work,env=environment,stdout=log,stderr=subprocess.STDOUT,timeout=1100)
        finally:
            display.terminate();display.wait(timeout=15)
            after=hashes(args.source_world);(work/'source-world-after.json').write_text(json.dumps(after,indent=2))
            if source_hashes!=after:raise RuntimeError('Source world changed during isolated validation')
    if completed.returncode or not (work/'R1_PACK_PASS.txt').exists():raise SystemExit(f'Full pack validation failed: {work}/client.log')
    print((work/'R1_PACK_PASS.txt').read_text());print(f'Source world unchanged; artifacts {work}')
if __name__=='__main__':main()
