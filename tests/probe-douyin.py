# Can a phone get Douyin videos WITHOUT the watermark? Tries the known no-watermark addresses and the computer page's own data API.
import json, re, subprocess, sys, time, urllib.request, os
from playwright.sync_api import sync_playwright

IDS = ['7704939941600323939', '6961737553342991651']
ANDROID = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36'
DESKTOP = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
APP = 'com.ss.android.ugc.aweme/310701 (Linux; U; Android 14; zh_CN; Pixel 8; Build/UQ1A.240205.004; Cronet/TTNetVersion:3c3d9a2b 2024-05-23 QuicVersion:182d68c8 2024-05-28)'
os.makedirs('shots', exist_ok=True)
HEADFUL = os.environ.get('HEADFUL') == '1'

def log(*a):
    print(*a, flush=True)

def frame(url, ua, name):
    """Save one frame at 1.5 s so the watermark can be checked by eye."""
    out = f'shots/{name}.jpg'
    r = subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-user_agent', ua, '-headers', 'Referer: https://www.douyin.com/\r\n',
                        '-ss', '1.5', '-i', url, '-frames:v', '1', '-vf', 'scale=360:-2', out], capture_output=True, text=True, timeout=60)
    p = subprocess.run(['ffprobe', '-v', 'error', '-user_agent', ua, '-select_streams', 'v:0', '-show_entries', 'stream=codec_name,width,height', '-of', 'csv=p=0', url],
                       capture_output=True, text=True, timeout=60)
    return (out if os.path.exists(out) else 'no frame ' + r.stderr[-120:]), p.stdout.strip()

def head(url, ua, ref=True):
    h = {'User-Agent': ua, 'Range': 'bytes=0-1023'}
    if ref: h['Referer'] = 'https://www.douyin.com/'
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=20) as r:
            return f'{r.status} {r.headers.get("Content-Type")} {r.headers.get("Content-Range")} -> {r.geturl()[:50]}'
    except Exception as e:
        return f'ERR {e}'

def mobile_item(p, vid):
    b = p.chromium.launch()
    ctx = b.new_context(user_agent=ANDROID, viewport={'width': 412, 'height': 860}, is_mobile=True, has_touch=True, locale='zh-CN')
    page = ctx.new_page()
    page.goto(f'https://www.douyin.com/video/{vid}', wait_until='domcontentloaded', timeout=40000)
    item = None
    for i in range(25):
        time.sleep(1)
        try:
            rd = page.evaluate('() => { try { return JSON.stringify(window._ROUTER_DATA) } catch (e) { return null } }')
        except Exception:
            rd = None
        if rd:
            for k, v in json.loads(rd).get('loaderData', {}).items():
                vi = (v or {}).get('videoInfoRes') if isinstance(v, dict) else None
                if vi and vi.get('item_list'):
                    item = vi['item_list'][0]
        if item: break
    b.close()
    return item

def desktop_api(p, vid):
    args = ['--disable-blink-features=AutomationControlled']
    b = p.chromium.launch(headless=not HEADFUL, args=args)
    ctx = b.new_context(user_agent=DESKTOP, viewport={'width': 1280, 'height': 800}, locale='zh-CN')
    ctx.add_init_script("Object.defineProperty(navigator, 'webdriver', {get: () => undefined})")
    page = ctx.new_page()
    got = {}
    def on_resp(r):
        if '/aweme/v1/web/aweme/detail' in r.url:
            try:
                t = r.text()
                got['page'] = (r.status, len(t), t[:120] if len(t) < 200 else '')
                if t.startswith('{') and json.loads(t).get('aweme_detail'):
                    got['detail'] = json.loads(t)['aweme_detail']
            except Exception as e:
                got['page_err'] = str(e)
    page.on('response', on_resp)
    t0 = time.time()
    try:
        page.goto(f'https://www.douyin.com/video/{vid}', wait_until='domcontentloaded', timeout=40000)
    except Exception as e:
        log('  goto err', e)
    for i in range(15):
        time.sleep(1)
        if 'detail' in got: break
    log(f'  D{"-headful" if HEADFUL else ""} {vid} page detail response: {got.get("page")} after {time.time()-t0:.0f}s url={page.url[:70]}')
    # ask the page itself to fetch the detail (Douyin's own security script signs it)
    if 'detail' not in got:
        try:
            res = page.evaluate("""async (id) => {
                const q = new URLSearchParams({device_platform: 'webapp', aid: '6383', channel: 'channel_pc_web', aweme_id: id, pc_client_type: '1',
                    version_code: '190500', version_name: '19.5.0', cookie_enabled: 'true', platform: 'PC', downlink: '10'});
                const r = await fetch('/aweme/v1/web/aweme/detail/?' + q.toString(), {credentials: 'include'});
                return [r.status, await r.text()];
            }""", vid)
            st, body = res
            log(f'  in-page fetch: {st} len={len(body)} {body[:120] if len(body) < 300 else ""}')
            if body.startswith('{') and json.loads(body).get('aweme_detail'):
                got['detail'] = json.loads(body)['aweme_detail']
        except Exception as e:
            log('  in-page fetch err', e)
    d = got.get('detail')
    if d:
        v = d['video']
        log('  detail desc', repr(d.get('desc', '')[:30]), 'w/h', v.get('width'), v.get('height'))
        pa = v.get('play_addr', {})
        log('  play_addr', pa.get('uri'), [u[:90] for u in pa.get('url_list', [])][:3])
        for br in (v.get('bit_rate') or [])[:8]:
            bp = br.get('play_addr', {})
            log('  bit_rate', br.get('gear_name'), br.get('is_h265'), bp.get('width'), bp.get('height'), br.get('bit_rate'), (bp.get('url_list') or [''])[0][:90])
        u = (pa.get('url_list') or [''])[0]
        if u:
            log('  fetch play_addr outside:', head(u, ANDROID), '| no referer:', head(u, ANDROID, False))
            log('  frame', frame(u, DESKTOP, f'desktop-api-{vid}'))
    b.close()

with sync_playwright() as p:
    for vid in IDS:
        log('==', vid)
        item = mobile_item(p, vid)
        if not item:
            log('  no mobile item'); continue
        v = item['video']
        uri = v['play_addr']['uri']
        wm = v['play_addr']['url_list'][0]
        log('  uri', uri, 'w/h', v.get('width'), v.get('height'), 'keys', sorted(v)[:25])
        log('  download_addr', json.dumps(v.get('download_addr'))[:200])
        log('  playwm frame', frame(wm, ANDROID, f'wm-{vid}'))
        cands = {
            'snssdk-play-1080': f'https://aweme.snssdk.com/aweme/v1/play/?video_id={uri}&ratio=1080p&line=0',
            'snssdk-play-720': f'https://aweme.snssdk.com/aweme/v1/play/?video_id={uri}&ratio=720p&line=0',
            'iesdouyin-play': f'https://www.iesdouyin.com/aweme/v1/play/?video_id={uri}&ratio=720p&line=0',
            'amemv-play': f'https://api.amemv.com/aweme/v1/play/?video_id={uri}&ratio=720p&line=0',
            'amemv-h2-play': f'https://api-h2.amemv.com/aweme/v1/play/?video_id={uri}&ratio=1080p&line=0',
            'douyin-play': f'https://www.douyin.com/aweme/v1/play/?video_id={uri}&ratio=720p&line=0',
            'snssdk-play-nor': f'https://aweme.snssdk.com/aweme/v1/play/?video_id={uri}',
        }
        for name, u in cands.items():
            for uan, ua in (('android', ANDROID), ('app', APP)):
                r = head(u, ua)
                log(f'  {name:18} {uan:7} {r}')
                if r.startswith('20'):
                    log('     frame', frame(u, ua, f'{name}-{uan}-{vid}'))
                    break
        desktop_api(p, vid)
