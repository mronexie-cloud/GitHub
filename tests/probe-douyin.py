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
for vid in IDS:
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

with sync_playwright() as p:
    for vid in IDS[:2]:
        browse(p, 'desktop', DESKTOP, False, f'https://www.douyin.com/video/{vid}', vid)
        browse(p, 'android-www', ANDROID, True, f'https://www.douyin.com/video/{vid}', vid)
        browse(p, 'android-share', ANDROID, True, f'https://www.iesdouyin.com/share/video/{vid}/', vid)
        browse(p, 'iphone-share', IPHONE, True, f'https://www.iesdouyin.com/share/video/{vid}/', vid)
