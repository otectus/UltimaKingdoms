#!/usr/bin/env python3
"""Exercise packaged Forge server restart and datapack reload without touching pack instances."""
import argparse, gzip, io, json, pathlib, re, shutil, struct, subprocess, time

def read_nbt(path):
    f=io.BytesIO(gzip.open(path,'rb').read())
    def num(fmt): return struct.unpack('>'+fmt,f.read(struct.calcsize(fmt)))[0]
    def string(): return f.read(num('H')).decode()
    def payload(t):
        if t in (1,2,3,4,5,6): return num({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[t])
        if t==7:return f.read(num('i'))
        if t==8:return string()
        if t==9:
            child=num('B');return [payload(child) for _ in range(num('i'))]
        if t==10:
            result={}
            while (child:=num('B'))!=0:
                name=string();result[name]=payload(child)
            return result
        if t in (11,12):return [num('i' if t==11 else 'q') for _ in range(num('i'))]
        raise ValueError(t)
    typ=num('B');string();return payload(typ)

def main():
    p=argparse.ArgumentParser();p.add_argument('--mca',action='store_true');p.add_argument('--lifecycle-only',action='store_true');p.add_argument('--live-reload-only',action='store_true');p.add_argument('--commands-only',action='store_true');p.add_argument('--work-dir',type=pathlib.Path);p.add_argument('--extra-mod',type=pathlib.Path,action='append',default=[]);args=p.parse_args()
    root=pathlib.Path(__file__).resolve().parents[2];work=root/'build'/('production-mca' if args.mca else 'production-standalone')
    if args.live_reload_only:
        from live_reload import run
        run(root,args.mca);return
    if args.lifecycle_only:work=work.with_name(work.name+'-lifecycle')
    if args.commands_only:work=work.with_name(work.name+'-commands')
    if args.work_dir:work=args.work_dir.resolve()
    if work.exists():raise SystemExit(f'Refusing to reuse runtime directory {work}')
    work.mkdir(parents=True);(work/'libraries').symlink_to(root/'build/forge-server/libraries',target_is_directory=True)
    properties=dict(line.strip().split('=',1) for line in (root/'gradle.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
    (work/'mods').mkdir(); jar=root/'build/libs'/f"{properties['mod_id']}-{properties['mod_version']}.jar";shutil.copy2(jar,work/'mods'/jar.name)
    if args.mca:
        source=pathlib.Path('/home/otectus/Documents/curseforge/minecraft/Instances/Ultima/mods')
        for name in ('minecraft-comes-alive-7.6.26+1.20.1-universal.jar','architectury-9.2.14-forge.jar'):shutil.copy2(source/name,work/'mods'/name)
    for extra in args.extra_mod:shutil.copy2(extra,work/'mods'/extra.name)
    if args.lifecycle_only or args.commands_only:
        harness=root/'build/test-artifacts'/f"{properties['mod_id']}-{properties['mod_version']}-acceptance.jar";shutil.copy2(harness,work/'mods'/harness.name)
    shutil.copy2('/home/otectus/Projects/MCACrime/run/eula.txt',work/'eula.txt')
    (work/'server.properties').write_text('online-mode=false\nserver-ip=127.0.0.1\nserver-port=0\nlevel-type=minecraft:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\ngenerator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\n')
    launcher='@libraries/net/minecraftforge/forge/1.20.1-47.4.23/unix_args.txt'
    import atexit
    running=[]
    def cleanup():
        for process in running:
            if process.poll() is None:
                try: process.stdin.write('stop\n');process.stdin.flush();process.wait(timeout=30)
                except Exception: process.terminate()
    atexit.register(cleanup)
    def boot(stage):
        log=work/f'{stage}.log';out=log.open('w');proc=subprocess.Popen(['java','-Xmx2G',*(['-Dultima.acceptance.commands=true'] if args.commands_only else []),launcher,'nogui'],cwd=work,stdin=subprocess.PIPE,stdout=out,stderr=out,text=True)
        running.append(proc);wait(proc,log,'Done (',180);return proc,log,out
    def wait(proc,log,text,timeout=60):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            if text in log.read_text(errors='replace'):return
            if proc.poll() is not None:raise RuntimeError(f'Exited {proc.returncode}: {log}')
            time.sleep(.25)
        proc.terminate();raise RuntimeError(f'Timeout waiting {text}: {log}')
    def send(proc,cmd):proc.stdin.write(cmd+'\n');proc.stdin.flush()
    def stop(proc,out):send(proc,'stop');assert proc.wait(timeout=60)==0;out.close()
    def records():return read_nbt(work/'world/data/ultima_kingdoms_settlements.dat')['data']['Settlements']
    if args.lifecycle_only or args.commands_only:
        proc,log,out=boot('mca-lifecycle');assert proc.wait(timeout=180)==0;out.close()
        result=(work/('command-results.txt' if args.commands_only else 'mca-lifecycle-results.txt')).read_text();print(result,end='');assert result.startswith('PASS ');print(f'Artifacts: {work}');return
    proc,log,out=boot('01-create')
    mob='mca:male_villager' if args.mca else 'minecraft:villager'
    commands=['execute positioned 1000 64 1000 run ultima village create 32 InitialFord','ultima village rename InitialFord Bellmeadow','ultima village setkingdom Bellmeadow ultima_kingdoms:serenum','forceload add 1000 1000',
              f'summon {mob} 1000 65 1000 {{NoAI:1b,PersistenceRequired:1b,Tags:["uk_acceptance"]}}',
              'ultima citizen setorigin @e[tag=uk_acceptance,limit=1] Bellmeadow',
              'ultima citizen setresidence @e[tag=uk_acceptance,limit=1] Bellmeadow','save-all flush']
    for cmd in commands:send(proc,cmd)
    wait(proc,log,'Saved the game');stop(proc,out)
    first=next(r for r in records() if r['DisplayName']=='Bellmeadow');assert first['Kingdom']=='ultima_kingdoms:serenum';print('PASS production create/rename/assign/save',flush=True)
    pack=work/'world/datapacks/acceptance';data=pack/'data/ultima_kingdoms/ultima_kingdoms/biome_rules';data.mkdir(parents=True)
    (pack/'pack.mcmeta').write_text(json.dumps({'pack':{'pack_format':15,'description':'Acceptance reload fixture'}}))
    rule=data/'test_override.json';rule.write_text(json.dumps({'schema':1,'kingdom':'ultima_kingdoms:lunari','priority':9999,'include':['minecraft:plains'],'exclude':[]}))
    proc,log,out=boot('02-restart-rules');send(proc,'save-all flush');wait(proc,log,'Saved the game')
    second=next(r for r in records() if r['DisplayName']=='Bellmeadow')
    assert (first['Id'],first['DisplayName'],first['Kingdom'])==(second['Id'],second['DisplayName'],second['Kingdom']);print('PASS production restart with changed rules preserves identity',flush=True)
    send(proc,'ultima village reclassify Bellmeadow');send(proc,'data get entity @e[tag=uk_acceptance,limit=1] ForgeData');send(proc,'save-all flush');time.sleep(2)
    third=next(r for r in records() if r['DisplayName']=='Bellmeadow');assert third['Id']==first['Id'] and third['Kingdom']=='ultima_kingdoms:lunari';print('PASS explicit reclassification applies new rule',flush=True)
    assert 'OriginKingdom: \"ultima_kingdoms:serenum\"' in log.read_text(errors='replace'),'Historical citizen origin not retained after restart/reclassification'
    print('PASS citizen historical origin retained across restart/reclassification',flush=True)
    rule.write_text('{ broken json');send(proc,'reload');wait(proc,log,'Failed to execute reload',90)
    send(proc,'execute positioned 2000 64 2000 run ultima village create 32 AfterInvalidReload');send(proc,'save-all flush');time.sleep(2);stop(proc,out)
    final=next(r for r in records() if r['DisplayName']=='AfterInvalidReload');assert final['Kingdom']=='ultima_kingdoms:lunari';print('PASS invalid reload retained last committed definitions',flush=True)
    if args.mca:assert re.search(r'Attached MCA 7\.6\.26\+1\.20\.1 integration through package root forge\.net\.(?:conczin\.)?mca ',log.read_text());print('PASS production exact MCA adapter startup',flush=True)
    # Verify the real SavedData storage path refuses future schemas without overwriting bytes.
    import hashlib
    save=work/'world/data/ultima_kingdoms_settlements.dat';original=save.read_bytes()
    raw=gzip.decompress(original);marker=b'\x03\x00\x06Schema\x00\x00\x00\x01'
    assert marker in raw
    save.write_bytes(gzip.compress(raw.replace(marker,b'\x03\x00\x06Schema\x00\x00\x00\x63')))
    before=hashlib.sha256(save.read_bytes()).hexdigest();rule.unlink()
    refusal=work/'03-future-schema.log'
    with refusal.open('w') as out:
        proc=subprocess.Popen(['java','-Xmx2G',*(['-Dultima.acceptance.commands=true'] if args.commands_only else []),launcher,'nogui'],cwd=work,stdin=subprocess.PIPE,stdout=out,stderr=out,text=True)
        try: proc.wait(timeout=120)
        except subprocess.TimeoutExpired: proc.terminate();proc.wait(timeout=15);raise RuntimeError('Future schema server did not refuse startup')
    assert 'Unsupported Ultima Kingdoms save schema 99' in refusal.read_text(errors='replace')
    assert hashlib.sha256(save.read_bytes()).hexdigest()==before,'Refused future save was overwritten'
    save.write_bytes(original)
    print('PASS actual future-schema startup refusal preserves original bytes',flush=True)
    print(f'Artifacts: {work}',flush=True)
if __name__=='__main__':main()
