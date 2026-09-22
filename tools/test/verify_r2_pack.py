#!/usr/bin/env python3
"""Verify R2 source exports and the bounded paid commission before staging."""
import hashlib,json,pathlib
ROOT=pathlib.Path(__file__).resolve().parents[2]
def main():
    count=0
    for provider in ('MCAQuests','MCACrime'):
        exported=ROOT/'pack/r2/providers'/provider
        source=exported/('sources' if (exported/'sources').is_dir() else 'files')
        changes=json.loads((exported/'changes.json').read_text())
        for change in changes:
            relative=pathlib.PurePosixPath(change['path'])
            assert not relative.is_absolute() and '..' not in relative.parts
            expected=change['after_sha256']
            for path in (source/relative,ROOT/'build/r2-provider-work'/provider/relative):
                assert hashlib.sha256(path.read_bytes()).hexdigest()==expected,f'Stale provider export: {path}'
            count+=1
    quest=json.loads((ROOT/'pack/r2/kubejs/data/ultima/mcaquests/quests/civic/lamplighters/workshop_lanterns.json').read_text())
    assert quest['id']=='ultima:civic/lamplighters/workshop_lanterns' and quest['institutional_commission'] is True
    assert quest['repeat']['type']=='once' and quest['turn_in']['mode']=='original_giver'
    assert quest['rewards']==[{'type':'mcaquests:item','item':'minecraft:emerald','count':6}]
    assert quest['objectives']==[{'type':'mcaquests:item_delivery','item':'minecraft:lantern','count':4,'consume':True}]
    assert 'conditions' in quest,'Older providers must reject the new required condition'
    print(f'PASS R2: {count} provider source exports match built checkouts; fixed native commission terms verified')
if __name__=='__main__':main()
