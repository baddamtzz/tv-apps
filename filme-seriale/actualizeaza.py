#!/usr/bin/env python3
"""Actualizează topurile IMDb și contul Letterboxd și reconstruiește index.html.

Rulează:  python3 actualizeaza.py            (cont Letterboxd demonstrativ)
          python3 actualizeaza.py USERNAME   (contul tău Letterboxd)
          LB_USER=USERNAME python3 actualizeaza.py

Surse:
  - datele oficiale IMDb (datasets.imdbws.com, doar uz necomercial)
  - IMDb suggestion (poze și actori), TVMaze (descrieri seriale), Wikipedia (descrieri filme)
  - fluxul RSS public al contului Letterboxd
Pozele și metadatele se păstrează în cache/, deci rulările următoare sunt rapide.
"""
import base64, concurrent.futures as cf, datetime, gzip, html, io, json, os, random, re, subprocess, sys, time
import urllib.parse, urllib.request
import xml.etree.ElementTree as ET

BASE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(BASE, 'cache')
for d in ('meta', 'posters', 'syn', 'lb'):
    os.makedirs(os.path.join(CACHE, d), exist_ok=True)
UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15'
_args = [a for a in sys.argv[1:] if not a.startswith('--')]
ARG = _args[0] if _args else os.environ.get('LB_USER')
LB_USER = ARG or 'bobec'
LB_DEMO = False
WEB = '--web' in sys.argv   # poze ca linkuri (pentru publicare), nu încorporate
YEAR = datetime.date.today().year


def get(url, timeout=60):
    return urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': UA}), timeout=timeout)


def log(*a):
    print(*a, flush=True)


# ---------- IMDb datasets ----------
def load_ratings():
    log('Descarc title.ratings.tsv.gz (~7 MB)...')
    with get('https://datasets.imdbws.com/title.ratings.tsv.gz', 180) as r:
        data = gzip.decompress(r.read()).decode('utf-8')
    out = {}
    for line in data.splitlines()[1:]:
        t, a, v = line.split('\t')
        if int(v) >= 10000:
            out[t] = (float(a), int(v))
    log('  titluri cu >=10000 voturi:', len(out))
    return out


def load_basics(ratings):
    log('Descarc și filtrez title.basics.tsv.gz (~190 MB, în flux, fără să îl păstrez)...')
    items = []
    with get('https://datasets.imdbws.com/title.basics.tsv.gz', 900) as r, gzip.GzipFile(fileobj=r) as g:
        for raw in io.TextIOWrapper(g, encoding='utf-8'):
            p = raw.rstrip('\n').split('\t')
            if len(p) < 9 or p[0] not in ratings or p[4] == '1' or p[1] not in ('movie', 'tvSeries', 'tvMiniSeries'):
                continue
            y = int(p[5]) if p[5].isdigit() else None
            if not y:
                continue
            rt, v = ratings[p[0]]
            items.append({'id': p[0], 'type': 'movie' if p[1] == 'movie' else 'series', 'title': p[2], 'year': y,
                          'end': int(p[6]) if p[6].isdigit() else None,
                          'runtime': int(p[7]) if p[7].isdigit() else None,
                          'genres': [] if p[8] == '\\N' else p[8].split(','), 'rating': rt, 'votes': v})
    log('  titluri păstrate:', len(items))
    return items


def wr(x, m=25000, c=7.0):
    return x['votes'] / (x['votes'] + m) * x['rating'] + m / (x['votes'] + m) * c


def build_rows(items):
    movies = [x for x in items if x['type'] == 'movie']
    series = [x for x in items if x['type'] == 'series']
    N = 16
    rows = []

    def add(rid, kind, title, sub, lst):
        rows.append({'id': rid, 'kind': kind, 'title': title, 'sub': sub, 'ids': [x['id'] for x in lst[:N]]})

    add('tm', 'movie', 'Top filme IMDb', 'Cele mai bine cotate, după nota ponderată', sorted([x for x in movies if x['votes'] >= 25000], key=wr, reverse=True))
    add('nm', 'movie', 'Noi și bine cotate', 'Filme din %d–%d, cu nota peste 7' % (YEAR - 1, YEAR), sorted([x for x in movies if x['year'] >= YEAR - 1 and x['votes'] >= 20000 and x['rating'] >= 7.0], key=lambda x: -x['votes']))
    add('gm', 'movie', 'Pietre ascunse', 'Nota peste 7,7, dar mai puțin cunoscute', sorted([x for x in movies if x['rating'] >= 7.7 and 25000 <= x['votes'] <= 120000 and x['year'] >= 1980], key=lambda x: wr(x, 10000), reverse=True))
    for g, gn in (('Sci-Fi', 'SF'), ('Thriller', 'Thriller'), ('Comedy', 'Comedie'), ('Animation', 'Animație'), ('Horror', 'Groază')):
        add('g_' + g, 'movie', gn, 'Cele mai bune filme din gen', sorted([x for x in movies if g in x['genres'] and x['votes'] >= 80000], key=wr, reverse=True))
    allx = movies + series
    for g, gn in (('Drama', 'Dramă'), ('Action', 'Acțiune'), ('Adventure', 'Aventură'), ('Crime', 'Crimă'), ('Mystery', 'Mister'), ('Romance', 'Romantic'),
                  ('Fantasy', 'Fantasy'), ('Family', 'Familie'), ('Documentary', 'Documentar'), ('War', 'Război'), ('History', 'Istorie'), ('Biography', 'Biografie')):
        add('gx_' + g, 'genre', gn, 'Filme și seriale, cele mai bine cotate', sorted([x for x in allx if g in x['genres'] and x['votes'] >= (60000 if g != 'Documentary' else 15000)], key=lambda x: wr(x, 50000), reverse=True))
    add('ts', 'series', 'Top seriale IMDb', 'Cele mai bine cotate, după nota ponderată', sorted([x for x in series if x['votes'] >= 50000], key=lambda x: wr(x, 50000), reverse=True))
    add('ns', 'series', 'Seriale noi', 'Începute din %d, cu nota peste 7,5' % (YEAR - 2), sorted([x for x in series if x['year'] >= YEAR - 2 and x['votes'] >= 15000 and x['rating'] >= 7.5], key=lambda x: -x['votes']))
    for g, gn in (('Crime', 'Crimă'), ('Comedy', 'Comedie'), ('Sci-Fi', 'SF și fantasy')):
        add('s_' + g, 'series', gn, 'Cele mai bune seriale din gen', sorted([x for x in series if g in x['genres'] and x['votes'] >= 50000], key=lambda x: wr(x, 50000), reverse=True))
    return [r for r in rows if len(r['ids']) >= 5]


# ---------- enrichment ----------
import threading
_wlock = threading.Lock()
_wlast = [0.0]
_w429 = [0]
WIKI_UA = 'filme-seriale-personal/1.0 (proiect personal de launcher TV; uz necomercial)'


def jget(url):
    with get(url, 30) as r:
        return json.loads(r.read().decode('utf-8'))


def wget(url):
    for attempt in range(5):
        with _wlock:
            wait = 1.0 - (time.time() - _wlast[0])
            if wait > 0:
                time.sleep(wait)
            _wlast[0] = time.time()
        try:
            req = urllib.request.Request(url, headers={'User-Agent': WIKI_UA})
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.loads(r.read().decode('utf-8'))
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt == 4:
                raise
            pause = min(int(e.headers.get('Retry-After') or 15 * (attempt + 1)), 90)
            log('  Wikimedia cere pauză %ds (%s)' % (pause, url[:70]))
            time.sleep(pause)


def meta(tid):
    f = os.path.join(CACHE, 'meta', tid + '.json')
    if os.path.exists(f):
        return json.load(open(f))
    m = {}
    try:
        d = jget('https://v3.sg.media-imdb.com/suggestion/t/%s.json' % tid)
        x = next((e for e in d.get('d', []) if e.get('id') == tid), None)
        if x:
            m = {'img': (x.get('i') or {}).get('imageUrl'), 'cast': x.get('s')}
    except Exception as e:
        log('  meta eșuat', tid, e)
        return {}
    json.dump(m, open(f, 'w'))
    time.sleep(0.15)
    return m


def resize(src, dst, w=220):
    r = subprocess.run(['sips', '--resampleWidth', str(w), '-s', 'format', 'jpeg', '-s', 'formatOptions', '68', src, '--out', dst], capture_output=True)
    return r.returncode == 0 and os.path.exists(dst)


def poster(key, url):
    f = os.path.join(CACHE, 'posters', key + '.jpg')
    if os.path.exists(f):
        return f
    if not url:
        return None
    try:
        tmp = f + '.raw'
        with get(url, 30) as r, open(tmp, 'wb') as o:
            o.write(r.read())
        ok = resize(tmp, f)
        if not ok:
            os.replace(tmp, f)
        elif os.path.exists(tmp):
            os.remove(tmp)
        return f
    except Exception as e:
        log('  poză eșuată', key, e)
        return None


def strip(s):
    return re.sub(r'\s+', ' ', html.unescape(re.sub(r'<[^>]+>', ' ', s or ''))).strip()


def short(s, n=360):
    if len(s) <= n:
        return s
    cut = s[:n]
    i = max(cut.rfind('. '), cut.rfind('! '), cut.rfind('? '))
    return cut[:i + 1] if i > 120 else cut.rsplit(' ', 1)[0] + '…'


WIKI = {}


def wiki_fill(ids):
    """Descrieri de pe Wikipedia (ro, apoi en), în loturi: IMDb id -> articol prin API-ul Wikidata, apoi începutul articolului."""
    ids = sorted(set(ids))
    links = {}
    WD = 'https://www.wikidata.org/w/api.php?format=json&'
    try:
        qids = []
        for i in range(0, len(ids), 25):
            q = 'haswbstatement:' + '|'.join('P345=%s' % x for x in ids[i:i + 25])
            d = wget(WD + 'action=query&list=search&srlimit=50&srsearch=' + urllib.parse.quote(q))
            qids += [h['title'] for h in d['query']['search']]
        for i in range(0, len(qids), 25):
            d = wget(WD + 'action=wbgetentities&props=claims|sitelinks&sitefilter=enwiki|rowiki&ids=' + '|'.join(qids[i:i + 25]))
            for e in d.get('entities', {}).values():
                imdb = [c['mainsnak']['datavalue']['value'] for c in e.get('claims', {}).get('P345', []) if 'datavalue' in c['mainsnak']]
                sl = {k[:2]: v['title'] for k, v in e.get('sitelinks', {}).items()}
                for t in imdb:
                    if t in ids:
                        links[t] = sl
        for lang in ('ro', 'en'):
            want = [(i, l[lang]) for i, l in links.items() if lang in l and i not in WIKI]
            for k in range(0, len(want), 20):
                part = want[k:k + 20]
                url = ('https://%s.wikipedia.org/w/api.php?action=query&prop=extracts&exintro=1&explaintext=1&exchars=800&redirects=1&format=json&titles=%s'
                       % (lang, urllib.parse.quote('|'.join(t for _, t in part))))
                d = wget(url).get('query', {})
                alias = {}
                for n in d.get('normalized', []):
                    alias[n['from']] = n['to']
                for n in d.get('redirects', []):
                    alias[n['from']] = n['to']
                ext = {p.get('title'): p.get('extract', '') for p in d.get('pages', {}).values()}
                for i, t in part:
                    t2 = alias.get(t, t)
                    t2 = alias.get(t2, t2)
                    txt = strip(ext.get(t2, ''))
                    if len(txt) >= 80 and 'may refer to' not in txt and 'poate să se refere' not in txt:
                        WIKI[i] = short(txt)
        log('  descrieri Wikipedia: %d din %d' % (len(WIKI), len(ids)))
    except Exception as e:
        log('  descrieri Wikipedia eșuate:', e)


def synopsis(x):
    f = os.path.join(CACHE, 'syn', x['id'] + '.json')
    if os.path.exists(f):
        return json.load(open(f))
    out = {}
    try:
        if x['type'] == 'series':
            d = jget('https://api.tvmaze.com/lookup/shows?imdb=' + x['id'])
            out = {'syn': short(strip(d.get('summary'))), 'net': (d.get('network') or d.get('webChannel') or {}).get('name'), 'status': d.get('status')}
        elif x['id'] in WIKI:
            out = {'syn': WIKI[x['id']]}
    except Exception as e:
        log('  descriere eșuată', x['id'], e)
        return {}
    if out:
        json.dump(out, open(f, 'w'))
    time.sleep(0.1)
    return out


def b64(path):
    return 'data:image/jpeg;base64,' + base64.b64encode(open(path, 'rb').read()).decode() if path and os.path.exists(path) else None


def enrich(x):
    m = meta(x['id'])
    url = m.get('img') and re.sub(r'\._V1_\.', '._V1_UX300_.', m['img'])
    s = synopsis(x)
    x = dict(x)
    x['cast'] = m.get('cast')
    x['poster'] = url if WEB else b64(poster(x['id'], url))
    x.update({k: v for k, v in s.items() if v})
    return x


# ---------- Letterboxd ----------
def letterboxd(user):
    ns = {'lb': 'https://letterboxd.com', 'tmdb': 'https://themoviedb.org'}
    try:
        with get('https://letterboxd.com/%s/rss/' % urllib.parse.quote(user), 30) as r:
            root = ET.fromstring(r.read())
    except Exception as e:
        log('Letterboxd eșuat:', e)
        return {'user': user, 'demo': LB_DEMO, 'error': 'Nu am putut citi contul „%s" de pe Letterboxd.' % user, 'items': []}
    items = []
    for it in root.iter('item'):
        ft = it.find('lb:filmTitle', ns)
        if ft is None:
            continue
        tid = (it.find('tmdb:movieId', ns).text if it.find('tmdb:movieId', ns) is not None else None) or str(abs(hash(ft.text)))
        desc = it.find('description').text or ''
        img = re.search(r'<img src="([^"]+)"', desc)
        paras = [strip(p) for p in re.findall(r'<p>(.*?)</p>', desc, re.S)]
        paras = [p for p in paras if p and not p.startswith('Watched on') and not p.startswith('Rewatched on')]
        rt = it.find('lb:memberRating', ns)
        items.append({'title': ft.text, 'year': int(it.find('lb:filmYear', ns).text) if it.find('lb:filmYear', ns) is not None else None,
                      'rating': float(rt.text) if rt is not None else None,
                      'date': it.find('lb:watchedDate', ns).text if it.find('lb:watchedDate', ns) is not None else None,
                      'rewatch': (it.find('lb:rewatch', ns).text == 'Yes') if it.find('lb:rewatch', ns) is not None else False,
                      'link': it.find('link').text, 'review': short(' '.join(paras), 260), 'tmdb': tid, 'img': img.group(1) if img else None})
    seen, out = set(), []
    for x in items:
        k = (x['title'], x['year'])
        if k in seen:
            continue
        seen.add(k)
        out.append(x)
    out = out[:30]

    def fin(x):
        x = dict(x)
        x['poster'] = x['img'] if WEB else b64(poster('lb_' + str(x['tmdb']), x['img']))
        x.pop('img', None)
        return x
    with cf.ThreadPoolExecutor(4) as ex:
        out = list(ex.map(fin, out))
    return {'user': user, 'demo': LB_DEMO, 'items': out}


# ---------- Letterboxd: de văzut ----------
LB_UA = 'tv-apps/1.0 (proiect personal; github.com/baddamtzz/tv-apps)'


def watchlist(user, ratings_by_id):
    names = []
    try:
        for page in (1, 2, 3):
            url = 'https://letterboxd.com/%s/watchlist/%s' % (urllib.parse.quote(user), '' if page == 1 else 'page/%d/' % page)
            req = urllib.request.Request(url, headers={'User-Agent': LB_UA})
            with urllib.request.urlopen(req, timeout=30) as r:
                body = r.read().decode('utf-8', 'replace')
            found = re.findall(r'data-item-name="([^"]+)" data-item-slug="([^"]+)"', body)
            if not found:
                break
            names += found
            time.sleep(1.2)
    except Exception as e:
        log('Watchlist Letterboxd eșuat:', e)
        if not names:
            return {'user': user, 'error': 'Nu am putut citi lista „de văzut" de pe Letterboxd.', 'items': []}
    out, seen = [], set()
    for nm, slug in names:
        nm = html.unescape(nm)
        m = re.match(r'^(.*) \((\d{4})\)$', nm)
        title, year = (m.group(1), int(m.group(2))) if m else (nm, None)
        if slug in seen:
            continue
        seen.add(slug)
        try:
            q = urllib.parse.quote(title.lower())
            d = jget('https://v3.sg.media-imdb.com/suggestion/%s/%s.json' % (re.sub(r'[^a-z0-9]', '', title.lower()[:1]) or 'a', q))
            norm = lambda t: re.sub(r'[^a-z0-9]', '', (t or '').lower())
            cand = [e for e in d.get('d', []) if str(e.get('id', '')).startswith('tt') and e.get('qid') in ('movie', 'tvMovie', 'tvMiniSeries')]
            pick = next((e for e in cand if norm(e.get('l')) == norm(title) and (not year or abs((e.get('y') or 0) - year) <= 1)), None) \
                or next((e for e in cand if year and abs((e.get('y') or 0) - year) <= 0 and norm(title) in norm(e.get('l'))), None)
            time.sleep(0.15)
        except Exception as e:
            log('  căutare eșuată', title, e)
            continue
        if not pick:
            continue
        base = dict(ratings_by_id.get(pick['id']) or {'id': pick['id'], 'type': 'movie', 'title': pick.get('l') or title, 'year': pick.get('y') or year, 'rating': None, 'votes': 0, 'genres': [], 'runtime': None})
        base['wl'] = True
        base['link'] = 'https://letterboxd.com/film/%s/' % slug
        out.append(base)
        if len(out) >= 40:
            break
    return {'user': user, 'items': out}


# ---------- main ----------
def main():
    t0 = time.time()
    ratings = load_ratings()
    items = load_basics(ratings)
    rows = build_rows(items)
    byid = {x['id']: x for x in items}
    ids = []
    for r in rows:
        for i in r['ids']:
            if i not in ids:
                ids.append(i)
    log('Letterboxd, de văzut @%s...' % LB_USER)
    wl = watchlist(LB_USER, byid)
    log('  de văzut: %d filme' % len(wl['items']))
    log('Descrieri Wikipedia...')
    wiki_fill([i for i in ids if byid[i]['type'] == 'movie'] + [x['id'] for x in wl['items']])
    log('Pozele și detaliile pentru %d titluri...' % len(ids))
    with cf.ThreadPoolExecutor(4) as ex:
        full = list(ex.map(lambda i: enrich(byid[i]), ids))
        wl['items'] = [x for x in ex.map(enrich, wl['items']) if x.get('poster')]
    data_items = {x['id']: x for x in full}
    for r in rows:
        r['ids'] = [i for i in r['ids'] if data_items[i].get('poster')]
    rows = [r for r in rows if len(r['ids']) >= 5]
    log('Letterboxd @%s...' % LB_USER)
    lb = letterboxd(LB_USER)
    top = [i for i in next(r for r in rows if r['id'] == 'tm')['ids'][:30]]
    pick = random.Random(datetime.date.today().isoformat()).choice(top)
    data = {'generated': datetime.datetime.now().strftime('%Y-%m-%d %H:%M'), 'rows': rows, 'items': data_items, 'lb': lb, 'wl': wl, 'pick': pick}
    json.dump(data, open(os.path.join(BASE, 'data.json'), 'w', encoding='utf-8'), ensure_ascii=False)
    build_html()
    log('Gata în %ds: %d titluri, %d rânduri, %d filme Letterboxd. index.html actualizat.' % (time.time() - t0, len(data_items), len(rows), len(lb['items'])))


def build_html():
    data = json.load(open(os.path.join(BASE, 'data.json'), encoding='utf-8'))
    tpl = open(os.path.join(BASE, 'template.html'), encoding='utf-8').read()
    assert '/*__DATA__*/null' in tpl
    out = tpl.replace('/*__DATA__*/null', json.dumps(data, ensure_ascii=False).replace('</', '<\\/'))
    open(os.path.join(BASE, 'index.html'), 'w', encoding='utf-8').write(out)


if __name__ == '__main__':
    if '--build' in sys.argv:
        build_html()
        log('index.html reconstruit din data.json')
    else:
        main()
