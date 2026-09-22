#!/usr/bin/env python3
"""Two real offline clients against an isolated packaged Forge dedicated server."""
import argparse, hashlib, json, os, pathlib, re, shutil, socket, subprocess, time, uuid
from production_client import allowed
ROOT=pathlib.Path(__file__).resolve().parents[2]
INSTALL=pathlib.Path('/home/otectus/Documents/curseforge/minecraft/Install')

def main():
    p=argparse.ArgumentParser();p.add_argument('--work-dir',type=pathlib.Path,required=True);args=p.parse_args()
    work=args.work_dir.resolve()
    if work.exists():raise SystemExit(f'Refusing to reuse {work}')
    work.mkdir(parents=True);server=work/'server';server.mkdir();(server/'mods').mkdir();(server/'libraries').symlink_to(ROOT/'build/forge-server/libraries',target_is_directory=True)
    mod=ROOT/'build/libs/ultima_kingdoms-0.1.0.jar';harness=ROOT/'build/test-artifacts/ultima_kingdoms-0.1.0-client-acceptance.jar'
    shutil.copy2(mod,server/'mods'/mod.name);shutil.copy2('/home/otectus/Projects/MCACrime/run/eula.txt',server/'eula.txt')
    with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    (server/'server.properties').write_text(f'online-mode=false\nenforce-secure-profile=false\nserver-ip=127.0.0.1\nserver-port={port}\nlevel-type=minecraft:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\n')
    vanilla=json.loads((INSTALL/'versions/1.20.1/1.20.1.json').read_text());forge=json.loads((INSTALL/'versions/forge-47.4.23/forge-47.4.23.json').read_text())
    libraries={}
    for lib in vanilla['libraries']+forge['libraries']:
        if allowed(lib.get('rules')):libraries[':'.join(lib['name'].split(':')[:2])+(':'+lib['name'].split(':')[3] if len(lib['name'].split(':'))>3 else '')]=lib
    cp=[str(INSTALL/'libraries'/lib['downloads']['artifact']['path']) for lib in libraries.values() if lib.get('downloads',{}).get('artifact')]
    cp.append(str(INSTALL/'versions/1.20.1/1.20.1.jar'))
    processes=[];logs=[]
    def spawn(cmd,cwd,log):
        out=log.open('w');logs.append(out);proc=subprocess.Popen(cmd,cwd=cwd,stdin=subprocess.PIPE,stdout=out,stderr=out,text=True,env=os.environ.copy());processes.append(proc);return proc
    def wait_until(test,seconds=180):
        end=time.monotonic()+seconds
        while time.monotonic()<end:
            if list(work.glob('*-FAIL.txt')):raise RuntimeError('Client failure: '+str(list(work.glob('*-FAIL.txt'))))
            if test():return
            if any(p.poll() not in (None,0) for p in processes):raise RuntimeError('Runtime exited with error; inspect logs')
            time.sleep(.25)
        raise RuntimeError('Timed out; inspect '+str(work))
    try:
        slog=server/'console.log';srv=spawn(['java','-Xmx2G','@libraries/net/minecraftforge/forge/1.20.1-47.4.23/unix_args.txt','nogui'],server,slog)
        wait_until(lambda:'Done (' in slog.read_text(errors='replace'))
        for role,name in [('leader','PoliticalLeader'),('stranger','PoliticalOther')]:
            folder=work/role;folder.mkdir();(folder/'mods').mkdir();(folder/'natives').mkdir()
            for jar in [mod,harness]:shutil.copy2(jar,folder/'mods'/jar.name)
            values={'auth_player_name':name,'version_name':'forge-47.4.23','game_directory':str(folder),'assets_root':str(INSTALL/'assets'),'assets_index_name':vanilla['assetIndex']['id'],'auth_uuid':str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+name).encode()).digest(),version=3)).replace('-',''),'auth_access_token':'0','clientid':'','auth_xuid':'','user_type':'legacy','version_type':'release','natives_directory':str(folder/'natives'),'launcher_name':'UltimaAcceptance','launcher_version':'1','classpath':':'.join(cp),'classpath_separator':':','library_directory':str(INSTALL/'libraries')}
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
            command=['java','-Xmx2G',f'-Dultima.clientTest.output={folder}','-Dultima.clientTest.multiplayer=true',f'-Dultima.clientTest.shared={work}',f'-Dultima.clientTest.role={role}',f'-Dultima.clientTest.server=127.0.0.1:{port}']+expand(vanilla['arguments']['jvm']+forge['arguments']['jvm'])+[forge['mainClass']]+expand(vanilla['arguments']['game']+forge['arguments']['game'])+['--width','960','--height','640']
            spawn(command,folder,folder/'console.log')
        wait_until(lambda:all(name+' logged in' in slog.read_text(errors='replace') or re.search(name+r'\[.*logged in',slog.read_text(errors='replace')) for name in ['PoliticalLeader','PoliticalOther']))
        commands=['op PoliticalLeader','execute positioned 0 -60 0 run ultima village create 16 PoliticalSeat','ultima village setkingdom PoliticalSeat ultima_kingdoms:serenum','execute positioned 160 -60 0 run ultima village create 16 WinterSeat','ultima village setkingdom WinterSeat ultima_kingdoms:lunari','execute as PoliticalLeader run ultima politics bootstrap ultima_kingdoms:serenum PoliticalSeat ultima_kingdoms:serenum_charter PoliticalLeader','execute as PoliticalLeader run ultima politics bootstrap ultima_kingdoms:lunari WinterSeat ultima_kingdoms:lunari_charter PoliticalLeader','execute as PoliticalLeader run ultima politics propose ultima_kingdoms:serenum ultima_kingdoms:lunari ultima_kingdoms:diplomatic_recognition Private multiplayer proposal']
        for command in commands:srv.stdin.write(command+'\n');srv.stdin.flush();time.sleep(.15)
        wait_until(lambda:'Political decision recorded:' in slog.read_text(errors='replace'))
        (work/'READY').write_text('ready\n');wait_until(lambda:(work/'leader-PASS.txt').exists() and (work/'stranger-PASS.txt').exists())
        for proc in processes[1:]:assert proc.wait(timeout=60)==0
        srv.stdin.write('stop\n');srv.stdin.flush();assert srv.wait(timeout=60)==0
        (work/'artifacts.sha256').write_text(''.join(hashlib.sha256(j.read_bytes()).hexdigest()+'  '+j.name+'\n' for j in [mod,harness]))
        print((work/'leader-PASS.txt').read_text().strip());print((work/'stranger-PASS.txt').read_text().strip());print('Artifacts: '+str(work))
    finally:
        for proc in processes:
            if proc.poll() is None:proc.terminate();proc.wait(timeout=20)
        for out in logs:out.close()
if __name__=='__main__':main()
