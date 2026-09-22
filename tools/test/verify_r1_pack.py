#!/usr/bin/env python3
"""Verify the R1 overlay preserves authored IDs and all existing mechanics."""
import argparse,hashlib,json,pathlib
ROOT=pathlib.Path(__file__).resolve().parents[2]
def main():
    p=argparse.ArgumentParser();p.add_argument('--pack',type=pathlib.Path,default=pathlib.Path('/home/otectus/Documents/curseforge/minecraft/Instances/Ultima'));args=p.parse_args()
    rows=json.loads((ROOT/'pack/r1/quest-manifest.json').read_text());assert len(rows)==12
    ids=set()
    for row in rows:
        original=args.pack/row['source_relative'];overlay=ROOT/'pack/r1'/row['source_relative']
        assert hashlib.sha256(original.read_bytes()).hexdigest()==row['source_sha256'],f'Source drift: {original}'
        assert hashlib.sha256(overlay.read_bytes()).hexdigest()==row['overlay_sha256'],f'Overlay drift: {overlay}'
        old=json.loads(original.read_text());new=json.loads(overlay.read_text());assert old['id']==new['id']==row['quest_id'];ids.add(new['id'])
        for key in ['offer','complete']:
            assert new['dialogue'][key]['extra'][0]==old['dialogue'][key]
            new['dialogue'][key]=old['dialogue'][key]
        assert new==old,f'Unexpected mechanical change in {overlay}'
    definition=json.loads((ROOT/'src/main/resources/data/ultima_kingdoms/ultima_factions/factions/lamplighters.json').read_text())
    assert ids=={d['quest_id'] for d in definition['deeds']}
    civic=json.loads((ROOT/'src/main/resources/data/ultima_kingdoms/ultima_factions/civic_network/lamplighters.json').read_text())
    assert set(civic['commissions'])==ids and set(civic['sponsor_quests'])<=ids
    # The independent route remains attainable entirely within the existing peaceful civic chain.
    peaceful=[d for d in definition['deeds'] if '/lamplighters/' in d['quest_id']]
    for service in definition['services']:
        assert sum(d['credit'] for d in peaceful)>=service['neutral_minimum_standing']
        assert len(peaceful)>=service['neutral_required_deeds']
    print('PASS 12 stable quest IDs; only dialogue context changed; rewards, prerequisites, objectives and giver rules preserved; peaceful neutral route attainable')
if __name__=='__main__':main()
