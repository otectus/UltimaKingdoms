"""Additional live reload and removed-definition acceptance on an already validated production world."""
import json,pathlib,subprocess,time
from production_runtime import read_nbt

def run(root,mca):
    work=root/'build'/('production-mca' if mca else 'production-standalone')
    path=work/'world/data/ultima_kingdoms_settlements.dat'
    def records():return read_nbt(path)['data']['Settlements']
    before=next(r for r in records() if r['DisplayName']=='Bellmeadow')
    pack=work/'world/datapacks/acceptance/data/ultima_kingdoms/ultima_kingdoms'
    kingdoms=pack/'kingdoms';kingdoms.mkdir(parents=True,exist_ok=True)
    custom=json.loads((root/'src/main/resources/data/ultima_kingdoms/ultima_kingdoms/kingdoms/serenum.json').read_text())
    custom.update(id='ultima_kingdoms:temporary_test',fallback=False,style_hints=[])
    customfile=kingdoms/'temporary_test.json';customfile.write_text(json.dumps(custom))
    log=work/'04-live-reload.log'
    with log.open('w') as out:
        proc=subprocess.Popen(['java','-Xmx2G','@libraries/net/minecraftforge/forge/1.20.1-47.4.23/unix_args.txt','nogui'],cwd=work,stdin=subprocess.PIPE,stdout=out,stderr=out,text=True)
        def send(cmd):proc.stdin.write(cmd+'\n');proc.stdin.flush()
        def wait(text,start=0,timeout=90):
            end=time.monotonic()+timeout
            while time.monotonic()<end:
                if text in log.read_text(errors='replace')[start:]:return
                if proc.poll() is not None:raise RuntimeError(f'Server exited: {log}')
                time.sleep(.2)
            raise RuntimeError(f'Timeout waiting {text}: {log}')
        def save():
            start=log.stat().st_size;send('save-all flush');wait('Saved the game',start)
        try:
            wait('Done (',timeout=180)
            send('execute positioned 3000 64 3000 run ultima village create 32 RemovedDefinitionVillage')
            send('ultima village setkingdom RemovedDefinitionVillage ultima_kingdoms:temporary_test');save()
            custombefore=next(r for r in records() if r['DisplayName']=='RemovedDefinitionVillage')
            assert custombefore['Kingdom']=='ultima_kingdoms:temporary_test'
            rule=pack/'biome_rules/live_override.json';rule.parent.mkdir(parents=True,exist_ok=True)
            rule.write_text(json.dumps({'schema':1,'kingdom':'ultima_kingdoms:yew','priority':20000,'include':['minecraft:plains'],'exclude':[]}))
            customfile.unlink();start=log.stat().st_size;send('ultima reload');wait('Reloaded Ultima Kingdoms data',start)
            send('ultima kingdom info ultima_kingdoms:temporary_test')
            send('execute positioned 4000 64 4000 run ultima village create 32 LiveReloadVillage');save()
            after=next(r for r in records() if r['DisplayName']=='Bellmeadow')
            assert (after['Id'],after['DisplayName'],after['Kingdom'])==(before['Id'],before['DisplayName'],before['Kingdom'])
            created=next(r for r in records() if r['DisplayName']=='LiveReloadVillage');assert created['Kingdom']=='ultima_kingdoms:yew'
            missing=next(r for r in records() if r['DisplayName']=='RemovedDefinitionVillage')
            assert (missing['Id'],missing['DisplayName'],missing['Kingdom'])==(custombefore['Id'],custombefore['DisplayName'],custombefore['Kingdom'])
            assert 'Unavailable Kingdom' in log.read_text(), 'Missing definition placeholder not presented'
            print('PASS successful live reload preserves established UUID/name/assignment and classifies new village with changed rules',flush=True)
            print('PASS removed custom definition retains save identity and resolves safe placeholder',flush=True)
            send('ultima village reclassify Bellmeadow');save();changed=next(r for r in records() if r['DisplayName']=='Bellmeadow')
            assert changed['Id']==before['Id'] and changed['Kingdom']=='ultima_kingdoms:yew'
            print('PASS explicit reclassification uses live committed rules',flush=True)
        finally:
            if proc.poll() is None:send('stop');proc.wait(timeout=60)
    print(f'Artifacts: {log}',flush=True)
