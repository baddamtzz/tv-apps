#!/usr/bin/env python3
"""Face site/fotbal/teams.json: echipele din campionatele de pe pagina Fotbal (ESPN, fără chei)."""
import json, os, sys, urllib.request

LIGI = ['eng.1', 'esp.1', 'ita.1', 'ger.1', 'fra.1', 'rou.1', 'por.1', 'ned.1', 'tur.1']
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'site', 'fotbal', 'teams.json')
UA = 'tv-apps/1.0 (proiect personal; github.com/baddamtzz/tv-apps)'


def main():
    out = {}
    for l in LIGI:
        try:
            req = urllib.request.Request('https://site.api.espn.com/apis/site/v2/sports/soccer/%s/teams' % l, headers={'User-Agent': UA})
            d = json.load(urllib.request.urlopen(req, timeout=30))
            t = [x['team'] for x in d['sports'][0]['leagues'][0]['teams']]
            out[l] = sorted([{'id': x['id'], 'n': x['displayName'], 'g': ((x.get('logos') or [{}])[0].get('href') or '')} for x in t], key=lambda x: x['n'])
            print(l, len(out[l]))
        except Exception as e:
            print('eșuat', l, e, file=sys.stderr)
    if not out:
        sys.exit('nu am putut citi nicio ligă')
    json.dump(out, open(OUT, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))


if __name__ == '__main__':
    main()
