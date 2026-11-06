# How can a phone get a Douyin video without signature cookies? Try a real browser (Chromium) the way an in-app WebView would.
import json, re, sys, time, urllib.request, urllib.parse, os
from playwright.sync_api import sync_playwright

IDS = ['7704939941600323939', '6961737553342991651', '7349092593364995338']
IPHONE = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1'
ANDROID = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36'
DESKTOP = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
os.makedirs('shots', exist_ok=True)

def log(*a):
    print(*a, flush=True)

# A. share page over plain HTTP: what does videoInfoRes hold?
for vid in []:
    for ua_name, ua in (('iphone', IPHONE), ('android', ANDROID)):
        try:
            req = urllib.request.Request(f'https://www.iesdouyin.com/share/video/{vid}/', headers={'User-Agent': ua, 'Referer': 'https://www.douyin.com/'})
            with urllib.request.urlopen(req, timeout=20) as r:
                html = r.read().decode('utf-8', 'replace')
            m = re.search(r'window\._ROUTER_DATA\s*=\s*(\{.*?\})\s*</script>', html, re.S)
            if not m:
                log('A', vid, ua_name, 'no ROUTER_DATA', len(html)); continue
            d = json.loads(m.group(1))['loaderData']
            page = d.get('video_(id)/page') or {}
            log('A', vid, ua_name, 'page keys', list(page)[:10])
            log('   videoInfoRes', json.dumps(page.get('videoInfoRes'), ensure_ascii=False)[:900])
        except Exception as e:
            log('A', vid, ua_name, 'ERR', e)

def browse(p, name, ua, mobile, url, vid, wait=25):
    ctx = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required']).new_context(
        user_agent=ua, viewport={'width': 412, 'height': 860} if mobile else {'width': 1280, 'height': 800},
        is_mobile=mobile, has_touch=mobile, locale='zh-CN')
    page = ctx.new_page()
    media, apis, detail = [], [], {}
    def on_req(r):
        u = r.url
        if re.search(r'douyinvod|zjcdn|bytevod|douyincdn|/aweme/v1/play|video_mp4|mime_type=video', u) and r.resource_type in ('media', 'xhr', 'fetch', 'other'):
            media.append((r.resource_type, u[:220]))
        if '/aweme/' in u and ('detail' in u or 'iteminfo' in u or 'multi' in u):
            apis.append(u[:200])
    def on_resp(r):
        u = r.url
        if '/aweme/v1/web/aweme/detail' in u or 'iteminfo' in u:
            try:
                t = r.text()
                detail['status'] = r.status
                detail['len'] = len(t)
                j = json.loads(t) if t.strip().startswith('{') else {}
                a = j.get('aweme_detail') or (j.get('item_list') or [None])[0]
                detail['has'] = bool(a)
                if a:
                    v = a.get('video', {})
                    detail['desc'] = a.get('desc', '')[:40]
                    detail['play'] = (v.get('play_addr', {}).get('url_list') or [''])[0][:160]
                    detail['bitrates'] = [(b.get('gear_name'), b.get('play_addr', {}).get('height')) for b in v.get('bit_rate') or []]
                else:
                    detail['body'] = t[:300]
            except Exception as e:
                detail['err'] = str(e)
    page.on('request', on_req)
    page.on('response', on_resp)
    t0 = time.time()
    try:
        page.goto(url, wait_until='domcontentloaded', timeout=40000)
    except Exception as e:
        log('B', name, 'goto err', e)
    srcs = []
    for i in range(wait):
        time.sleep(1)
        try:
            srcs = page.evaluate("""() => [...document.querySelectorAll('video, video source')].map(e => e.currentSrc || e.src || '').filter(Boolean)""")
        except Exception:
            srcs = []
        if (detail.get('has') or [s for s in srcs if s.startswith('http')]) and i > 4:
            break
    rd = None
    try:
        rd = page.evaluate("""() => { const e = document.getElementById('RENDER_DATA'); return e ? e.textContent.length : null }""")
    except Exception:
        pass
    title = ''
    try:
        title = page.title()
    except Exception:
        pass
    page.screenshot(path=f'shots/{name}-{vid}.png')
    log(f'B {name} {vid} {time.time()-t0:.0f}s final={page.url[:90]} title={title[:40]!r} RENDER_DATA={rd}')
    log('   video srcs:', [s[:160] for s in srcs][:4])
    log('   media reqs:', media[:4], 'count', len(media))
    log('   api reqs:', apis[:3])
    log('   detail:', json.dumps(detail, ensure_ascii=False)[:600])
    cookies = {c['name'] for c in ctx.cookies()}
    log('   cookies:', sorted(cookies)[:40])
    # can the found media URL be fetched outside the browser?
    try:
        rd2 = page.evaluate('''() => { try { return JSON.stringify(window._ROUTER_DATA) } catch (e) { return null } }''')
        if rd2:
            d = json.loads(rd2).get('loaderData', {})
            for k, v in d.items():
                vi = (v or {}).get('videoInfoRes') if isinstance(v, dict) else None
                if vi:
                    it = (vi.get('item_list') or [{}])[0]
                    vv = it.get('video', {})
                    log('   ROUTER item:', k, 'desc', repr(it.get('desc', '')[:30]), 'author', (it.get('author') or {}).get('nickname'), 'dur', vv.get('duration'), 'h', vv.get('height'),
                        'cover', bool((vv.get('cover') or {}).get('url_list')), 'play', (vv.get('play_addr') or {}).get('url_list', [''])[0][:140], 'bit_rate', len(vv.get('bit_rate') or []), 'images', bool(it.get('images')))
        else:
            log('   no _ROUTER_DATA')
    except Exception as e:
        log('   ROUTER err', e)
    for s in srcs:
        if not s.startswith('http'): continue
        for variant in (s, s.replace('ratio=720p', 'ratio=1080p'), s.replace('m.douyin.com', 'aweme.snssdk.com').replace('ratio=720p', 'ratio=1080p')):
            try:
                req = urllib.request.Request(variant, headers={'User-Agent': ua, 'Referer': 'https://www.douyin.com/', 'Range': 'bytes=0-1023'})
                with urllib.request.urlopen(req, timeout=20) as r:
                    log('   variant', variant[25:80], r.status, r.headers.get('Content-Type'), r.headers.get('Content-Range'), r.geturl()[:60])
            except Exception as e:
                log('   variant', variant[25:80], 'ERR', e)
            try:
                req = urllib.request.Request(variant, headers={'User-Agent': ua})
                with urllib.request.urlopen(req, timeout=20) as r:
                    log('     no-referer/no-range:', r.status, r.headers.get('Content-Type'), r.headers.get('Content-Length'))
            except Exception as e:
                log('     no-referer ERR', e)
        break
    cand = [s for s in srcs if s.startswith('http')] + [u for _, u in media]
    if detail.get('play'):
        cand.insert(0, detail['play'])
    for c in cand[:1]:
        full = c
        for s in srcs + [detail.get('play', '')]:
            if s.startswith(c[:150]): full = s
        try:
            req = urllib.request.Request(full, headers={'User-Agent': ua, 'Referer': 'https://www.douyin.com/', 'Range': 'bytes=0-1023'})
            with urllib.request.urlopen(req, timeout=20) as r:
                log('   fetch outside browser:', r.status, r.headers.get('Content-Type'), r.headers.get('Content-Range'))
        except Exception as e:
            log('   fetch outside browser ERR', e)
    ctx.browser.close()

def desktop_ssr(p, vid):
    # the computer version embeds the video info (RENDER_DATA) in the page itself
    b = p.chromium.launch()
    ctx = b.new_context(user_agent=DESKTOP, viewport={'width': 1280, 'height': 800}, locale='zh-CN')
    page = ctx.new_page()
    t0 = time.time()
    try:
        page.goto(f'https://www.douyin.com/video/{vid}', wait_until='domcontentloaded', timeout=40000)
    except Exception as e:
        log('C goto err', e)
    rd = None
    for i in range(20):
        time.sleep(1)
        try:
            rd = page.evaluate("() => { const e = document.getElementById('RENDER_DATA'); return e ? decodeURIComponent(e.textContent) : null }")
        except Exception:
            rd = None
        if rd: break
    log(f'C desktop-ssr {vid} {time.time()-t0:.0f}s url={page.url[:80]} RENDER_DATA={len(rd) if rd else None}')
    if rd:
        j = json.loads(rd)
        found = []
        def walk(n, path, d=0):
            if d > 14: return
            if isinstance(n, dict):
                aid = str(n.get('awemeId') or n.get('aweme_id') or '')
                v = n.get('video')
                if aid == vid and isinstance(v, dict):
                    found.append((path, n))
                for k, x in n.items(): walk(x, path + '.' + k, d + 1)
            elif isinstance(n, list):
                for i, x in enumerate(n[:50]): walk(x, path + f'[{i}]', d + 1)
        walk(j, '')
        log('   top keys', list(j)[:12])
        for path, n in found[:2]:
            v = n['video']
            log('   found at', path, 'video keys', list(v)[:30])
            log('   desc', repr(n.get('desc', '')[:30]), 'author', (n.get('authorInfo') or n.get('author') or {}).get('nickname'))
            pa = v.get('playAddr') or v.get('play_addr')
            log('   playAddr', json.dumps(pa, ensure_ascii=False)[:400])
            brl = v.get('bitRateList') or v.get('bit_rate') or []
            for b2 in brl[:6]:
                pa2 = b2.get('playAddr') or b2.get('play_addr')
                log('   bitrate', b2.get('gearName') or b2.get('gear_name'), b2.get('width'), b2.get('height'), b2.get('isH265'), b2.get('format'), json.dumps(pa2)[:200])
            srcs = []
            if isinstance(pa, list): srcs = [x.get('src') for x in pa if isinstance(x, dict)]
            elif isinstance(pa, dict): srcs = pa.get('url_list') or pa.get('urlList') or []
            for s0 in srcs[:3]:
                u = 'https:' + s0 if s0.startswith('//') else s0
                for hdr in ({'User-Agent': DESKTOP, 'Referer': 'https://www.douyin.com/', 'Range': 'bytes=0-1023'}, {'User-Agent': ANDROID, 'Range': 'bytes=0-1023'}):
                    try:
                        with urllib.request.urlopen(urllib.request.Request(u, headers=hdr), timeout=20) as r:
                            log('   fetch', u[:70], 'ref' if 'Referer' in hdr else 'noref', r.status, r.headers.get('Content-Type'), r.headers.get('Content-Range'))
                    except Exception as e:
                        log('   fetch', u[:70], 'ref' if 'Referer' in hdr else 'noref', 'ERR', e)
        if not found:
            log('   no aweme; sample', rd[:600])
    b.close()

with sync_playwright() as p:
    for vid in IDS[:2]:
        desktop_ssr(p, vid)
    for vid in IDS[:2]:
        browse(p, 'android-www', ANDROID, True, f'https://www.douyin.com/video/{vid}', vid)
