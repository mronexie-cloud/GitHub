# Which platforms can yt-dlp read without logging in (from a GitHub server)? Plus a Douyin share-page fallback.
import json, re, subprocess, urllib.request, time
URLS = {
  'YouTube': ['https://www.youtube.com/watch?v=jNQXAC9IVRw', 'https://www.youtube.com/shorts/BaW_jenozKc'],
  'TikTok': ['https://www.tiktok.com/@leenabhushan/video/6748451240264420610', 'https://www.tiktok.com/@barudakhb_/video/6984138651336838402'],
  'Douyin': ['https://www.douyin.com/video/6961737553342991651'],
  'Xiaohongshu': ['https://www.xiaohongshu.com/explore/6411cf99000000001300b6d9'],
  'X/Twitter': ['https://twitter.com/starwars/status/665052190608723968', 'https://twitter.com/captainamerica/status/719944021058060289'],
  'Instagram': ['https://www.instagram.com/reel/Chunk8-jurw/', 'https://www.instagram.com/p/BQ0eAlwhDrw/'],
  'Bilibili': ['https://www.bilibili.com/video/BV13x41117TL', 'https://www.bilibili.com/video/BV1bK411W797'],
  'Facebook': ['https://www.facebook.com/watch/?v=647537299265662', 'https://www.facebook.com/radiokicksfm/videos/3676516585958356/'],
}
print('yt-dlp', subprocess.run(['yt-dlp', '--version'], capture_output=True, text=True).stdout.strip())
for plat, urls in URLS.items():
    for u in urls:
        t = time.time()
        try:
            r = subprocess.run(['yt-dlp', '--dump-single-json', '--no-playlist', '--no-warnings', u], capture_output=True, text=True, timeout=90)
            if r.returncode == 0:
                j = json.loads(r.stdout)
                fm = j.get('formats') or []
                best = max((f.get('height') or 0 for f in fm), default=0)
                print(f'OK   {plat:12} {u}\n       title={j.get("title","")[:50]!r} formats={len(fm)} max_height={best} dur={j.get("duration")} {time.time()-t:.1f}s')
            else:
                err = [l for l in r.stderr.splitlines() if 'ERROR' in l]
                print(f'FAIL {plat:12} {u}\n       {(err[-1] if err else r.stderr[-300:])[:260]}')
        except Exception as e:
            print(f'FAIL {plat:12} {u}\n       {e}')

# Douyin without cookies: public share page used by the mobile app
UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1'
def get(url):
    req = urllib.request.Request(url, headers={'User-Agent': UA, 'Referer': 'https://www.douyin.com/'})
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.geturl(), r.read().decode('utf-8', 'replace')
for vid in ['6961737553342991651', '7349092593364995338']:
    try:
        final, html = get(f'https://www.iesdouyin.com/share/video/{vid}/')
        m = re.search(r'window\._ROUTER_DATA\s*=\s*(\{.*?\})</script>', html, re.S)
        if not m:
            print('FAIL Douyin-share', vid, 'no _ROUTER_DATA, page', len(html), 'bytes', html[:160].replace('\n', ' '))
            continue
        data = json.loads(m.group(1))['loaderData']
        item = None
        for k, v in data.items():
            if isinstance(v, dict) and v.get('videoInfoRes'):
                lst = v['videoInfoRes'].get('item_list') or []
                if lst: item = lst[0]
        if not item:
            print('FAIL Douyin-share', vid, 'no item; keys', list(data)[:5]); continue
        play = item['video']['play_addr']['url_list'][0].replace('playwm', 'play')
        req = urllib.request.Request(play, headers={'User-Agent': UA, 'Range': 'bytes=0-1023'})
        with urllib.request.urlopen(req, timeout=20) as r:
            ct, size = r.headers.get('Content-Type'), r.headers.get('Content-Range')
        print('OK   Douyin-share', vid, repr(item.get('desc', '')[:40]), 'video', ct, size)
    except Exception as e:
        print('FAIL Douyin-share', vid, e)
