#!/usr/bin/env python3
"""Offline packaged client acceptance using installed launcher libraries, isolated saves/mods."""
import json, pathlib, shutil, subprocess, sys, uuid, re, hashlib
ROOT=pathlib.Path(__file__).resolve().parents[2]
INSTALL=pathlib.Path('/home/otectus/Documents/curseforge/minecraft/Install')
PACK=pathlib.Path('/home/otectus/Documents/curseforge/minecraft/Instances/Ultima/mods')
WORK=ROOT/'build'/('production-client-blueprint'+(('-'+sys.argv[1]) if len(sys.argv)>1 else ''))

def allowed(rules):
    answer=not rules
    for rule in rules or []:
        os=rule.get('os',{})
        matches=(os.get('name','linux')=='linux' and os.get('arch','amd64') in ('amd64','x86_64') and not rule.get('features'))
        if matches: answer=rule['action']=='allow'
    return answer

def main():
    if WORK.exists(): raise SystemExit(f'Refusing to reuse {WORK}')
    WORK.mkdir(parents=True); (WORK/'mods').mkdir(); (WORK/'natives').mkdir()
    vanilla=json.loads((INSTALL/'versions/1.20.1/1.20.1.json').read_text())
    forge=json.loads((INSTALL/'versions/forge-47.4.23/forge-47.4.23.json').read_text())
    libraries={}
    for lib in vanilla['libraries']+forge['libraries']:
        if allowed(lib.get('rules')):
            libraries[':'.join(lib['name'].split(':')[:2]) + (':'+lib['name'].split(':')[3] if len(lib['name'].split(':'))>3 else '')]=lib
    cp=[]
    for lib in libraries.values():
        artifact=lib.get('downloads',{}).get('artifact')
        if artifact:
            path=INSTALL/'libraries'/artifact['path']
            if not path.exists():raise SystemExit(f'Missing library {path}')
            cp.append(str(path))
    cp.append(str(INSTALL/'versions/1.20.1/1.20.1.jar'))
    jars=[PACK/n for n in ['minecraft-comes-alive-7.6.26+1.20.1-universal.jar','architectury-9.2.14-forge.jar','townstead-0.7.6+1.20.1.jar']]
    jars+=list(PACK.glob('Patchouli-1.20.1-85*.jar'))
    jars+=list((ROOT/'build/integration-artifacts').glob('*.jar'))
    jars+=[ROOT/'build/test-artifacts/ultima_kingdoms-0.1.0-client-acceptance.jar']
    if len(jars)!=9:raise SystemExit(f'Expected 9 jars, found {jars}')
    for jar in jars:shutil.copy2(jar,WORK/'mods'/jar.name)
    (WORK/'artifacts.json').write_text(json.dumps({j.name:hashlib.sha256(j.read_bytes()).hexdigest() for j in jars},indent=2))
    values={'auth_player_name':'BlueprintTest','version_name':'forge-47.4.23','game_directory':str(WORK),'assets_root':str(INSTALL/'assets'),'assets_index_name':vanilla['assetIndex']['id'],'auth_uuid':uuid.uuid3(uuid.NAMESPACE_DNS,'OfflinePlayer:BlueprintTest').hex,'auth_access_token':'0','clientid':'','auth_xuid':'','user_type':'legacy','version_type':'release','natives_directory':str(WORK/'natives'),'launcher_name':'UltimaAcceptance','launcher_version':'1','classpath':':'.join(cp),'classpath_separator':':','library_directory':str(INSTALL/'libraries')}
    def expand(args):
        result=[]
        for arg in args:
            if isinstance(arg,dict):
                if not allowed(arg.get('rules')):continue
                arg=arg['value']
            for value in arg if isinstance(arg,list) else [arg]:
                expanded=re.sub(r'\$\{([^}]+)\}',lambda m:values[m[1]],value)
                if expanded.startswith('-DignoreList='): expanded+=',1.20.1.jar'
                result.append(expanded)
        return result
    cmd=['java','-Xmx4G',f'-Dultima.clientTest.output={WORK}','-Dultima.clientTest.blueprint=true']+expand(vanilla['arguments']['jvm']+forge['arguments']['jvm'])+[forge['mainClass']]+expand(vanilla['arguments']['game']+forge['arguments']['game'])+['--width','1280','--height','800']
    (WORK/'launch-command.json').write_text(json.dumps(cmd,indent=2))
    with (WORK/'client.log').open('w') as log:
        result=subprocess.run(cmd,cwd=WORK,stdout=log,stderr=subprocess.STDOUT,timeout=480)
    print(f'Client exit {result.returncode}; log {WORK}/client.log')
    if result.returncode or not (WORK/'BLUEPRINT_PASS.txt').exists() or not (WORK/'CONVERSATION_ENTRY_PASS.txt').exists():raise SystemExit('Client acceptance did not pass')
    print((WORK/'BLUEPRINT_PASS.txt').read_text())
if __name__=='__main__':main()
