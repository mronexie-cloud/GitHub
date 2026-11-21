"""万能下载 · 网页版服务器

The iPhone / web version of 万能下载 cannot read Douyin, TikTok, ... from the browser, so this
small server does it: it finds the video behind a shared link and streams the file back, so
Safari saves it like any other download. Same engine as the Android app (yt-dlp); Douyin is read
from its public share page, the way the Android app reads it from Douyin's own web page.

Endpoints
  GET /                         the web page (same page as ios/downloader)
  GET /api/health               {"ok": true}
  GET /api/info?url=TEXT        what the link points to (title, cover, platform, choices)
  GET /api/download?url=TEXT&q=best|1080|720|480|audio   the file itself (attachment)
"""
import json
import os
import re
import shutil
import tempfile
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
PORT = int(os.environ.get("PORT", "10000"))
MAX_BYTES = int(os.environ.get("MAX_MB", "800")) * 1024 * 1024
UA_IPHONE = ("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
             "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1")
UA_PC = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
         "Chrome/126.0 Safari/537.36")

PLATFORMS = [  # (key, name, host words)
    ("douyin", "抖音", ("douyin.com", "iesdouyin.com")),
    ("tiktok", "TikTok", ("tiktok.com",)),
    ("xhs", "小红书", ("xiaohongshu.com", "xhslink.com")),
    ("bili", "哔哩哔哩", ("bilibili.com", "b23.tv")),
    ("youtube", "YouTube", ("youtube.com", "youtu.be")),
    ("x", "X", ("x.com", "twitter.com")),
    ("ig", "Instagram", ("instagram.com",)),
    ("fb", "Facebook", ("facebook.com", "fb.watch")),
]


class Fail(Exception):
    def __init__(self, code, msg):
        super().__init__(msg)
        self.code = code


def platform_of(url):
    h = urllib.parse.urlparse(url).hostname or ""
    for key, name, hosts in PLATFORMS:
        if any(h == x or h.endswith("." + x) for x in hosts):
            return key, name
    return "other", "网页"


def extract_url(text):
    m = re.search(r"https?://[^\s<>\"'，。！）)】]+", text or "")
    if not m:
        raise Fail("NO_LINK", "没找到链接，请粘贴分享链接或分享文字")
    return m.group(0)


def http_get(url, ua=UA_IPHONE, referer=None, timeout=20):
    req = urllib.request.Request(url, headers={"User-Agent": ua, "Accept-Language": "zh-CN,zh;q=0.9"})
    if referer:
        req.add_header("Referer", referer)
    return urllib.request.urlopen(req, timeout=timeout)


def resolve(url):
    """Follows short links (v.douyin.com, b23.tv, xhslink.com, ...) to the real page."""
    short = ("v.douyin.com", "b23.tv", "xhslink.com", "vm.tiktok.com", "vt.tiktok.com", "fb.watch")
    h = urllib.parse.urlparse(url).hostname or ""
    if not any(h == s or h.endswith("." + s) for s in short):
        return url
    try:
        with http_get(url) as r:
            return r.geturl()
    except Exception:
        return url


# ------------------------------------------------------------------ Douyin (share page)
def douyin_id(url):
    m = re.search(r"(?:/video/|/note/|modal_id=|aweme_id=|/share/video/|/share/note/)(\d{8,})", url)
    return m.group(1) if m else None


DOUYIN_COOKIE = os.environ.get("DOUYIN_COOKIE", "").strip()


def douyin_get(url, ua=UA_IPHONE):
    req = urllib.request.Request(url, headers={"User-Agent": ua, "Accept-Language": "zh-CN,zh;q=0.9",
                                               "Referer": "https://www.douyin.com/"})
    if DOUYIN_COOKIE:
        req.add_header("Cookie", DOUYIN_COOKIE)
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.status, r.read().decode("utf-8", "replace")


def find_aweme(obj, vid, depth=0):
    """Finds the video record anywhere in Douyin's page data: a dict whose 'video' has 'play_addr'
    (or that holds 'images'), preferring the one whose aweme_id matches."""
    best = None
    if depth > 12:
        return None
    if isinstance(obj, dict):
        v = obj.get("video")
        if isinstance(v, dict) and (v.get("play_addr") or v.get("playAddr") or v.get("bit_rate")) or obj.get("images"):
            if str(obj.get("aweme_id") or obj.get("awemeId") or "") == vid:
                return obj
            best = obj
        for x in obj.values():
            r = find_aweme(x, vid, depth + 1)
            if r is not None:
                if str(r.get("aweme_id") or r.get("awemeId") or "") == vid:
                    return r
                best = best or r
    elif isinstance(obj, list):
        for x in obj:
            r = find_aweme(x, vid, depth + 1)
            if r is not None:
                if str(r.get("aweme_id") or r.get("awemeId") or "") == vid:
                    return r
                best = best or r
    return best


def douyin_probe(vid):
    """Tries Douyin's public share pages. Returns (item or None, notes for diagnosis)."""
    notes = []
    for name, url, ua in [
        ("share-video", f"https://www.iesdouyin.com/share/video/{vid}/", UA_IPHONE),
        ("share-note", f"https://www.iesdouyin.com/share/note/{vid}/", UA_IPHONE),
        ("m-douyin", f"https://m.douyin.com/share/video/{vid}", UA_IPHONE),
    ]:
        n = {"try": name}
        notes.append(n)
        try:
            n["status"], html = douyin_get(url, ua)
        except Exception as e:  # noqa
            n["error"] = str(e)[:120]
            continue
        n["size"] = len(html)
        m = re.search(r"window\._ROUTER_DATA\s*=\s*(\{.*?\})</script>", html, re.S)
        if not m:
            n["page"] = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", html))[:120]
            continue
        try:
            data = json.loads(m.group(1))
        except ValueError:
            n["page"] = "bad json"
            continue
        ld = data.get("loaderData") or {}
        n["keys"] = {k: (sorted(v.keys())[:12] if isinstance(v, dict) else type(v).__name__) for k, v in list(ld.items())[:6]}
        for v in ld.values():
            res = v.get("videoInfoRes") if isinstance(v, dict) else None
            if not isinstance(res, dict):
                continue
            items = res.get("item_list") or []
            n["items"] = len(items)
            fl = res.get("filter_list") or []
            if fl:
                n["filter"] = [{k: f.get(k) for k in ("filter_reason", "detail_msg", "notice") if f.get(k)} for f in fl][:2]
            if items:
                return items[0], notes
        found = find_aweme(data, vid)  # Douyin moves things around: look everywhere
        if found:
            n["found"] = "deep"
            return found, notes
    return None, notes


def douyin_info(url):
    vid = douyin_id(url)
    if not vid:
        raise Fail("BAD_LINK", "认不出这个抖音链接")
    item, notes = douyin_probe(vid)
    if not item:
        reason = ""
        for n in notes:
            for f in n.get("filter") or []:
                reason = f.get("detail_msg") or f.get("notice") or f.get("filter_reason") or reason
        print("douyin fail", vid, json.dumps(notes, ensure_ascii=False), flush=True)
        try:  # last try: yt-dlp's own Douyin reader
            d = ydl_info(f"https://www.douyin.com/video/{vid}")
            d.update({"platform": "douyin", "platformName": "抖音", "ydl": True})
            return d
        except Fail:
            pass
        msg = "抖音没有把这个视频给服务器"
        if reason:
            msg += "（抖音说：%s）" % reason
        msg += "。很可能是抖音不让海外服务器读取，这个视频请用安卓版下载。"
        raise Fail("DOUYIN_BLOCKED", msg)
    video = item.get("video") or {}
    pa = video.get("play_addr") or video.get("playAddr") or {}
    urls = (pa.get("url_list") or pa.get("urlList") or []) if isinstance(pa, dict) else []
    if isinstance(pa, list):  # some page versions: [{"src": ...}]
        urls = [x.get("src") for x in pa if isinstance(x, dict) and x.get("src")]
    if not urls:
        for br in video.get("bit_rate") or video.get("bitRateList") or []:
            p2 = br.get("play_addr") or br.get("playAddr") or {}
            urls = p2.get("url_list") or p2.get("urlList") or []
            if urls:
                break
    urls = [("https:" + u if u.startswith("//") else u) for u in urls if u]
    images = [((i.get("url_list") or [None])[0]) for i in (item.get("images") or [])]
    images = [i for i in images if i]
    if not urls and not images:
        raise Fail("NOT_FOUND", "没找到视频地址")
    play = urls[0].replace("playwm", "play") if urls else ""
    cv = video.get("cover") or video.get("origin_cover") or {}
    cover = ((cv.get("url_list") or cv.get("urlList") or [""]) if isinstance(cv, dict) else [str(cv)])[0]
    return {
        "platform": "douyin", "platformName": "抖音", "id": vid,
        "title": (item.get("desc") or "抖音视频").strip()[:80],
        "uploader": (item.get("author") or item.get("authorInfo") or {}).get("nickname", ""),
        "duration": round((video.get("duration") or 0) / 1000),
        "thumb": cover, "direct": play, "images": images,
    }


# ------------------------------------------------------------------ yt-dlp
def ydl_opts(extra=None, url=""):
    o = {"quiet": True, "no_warnings": True, "noplaylist": True, "socket_timeout": 20,
         "http_headers": {"User-Agent": UA_PC}}
    if DOUYIN_COOKIE and "douyin.com" in url:  # only ever sent to Douyin
        o["http_headers"]["Cookie"] = DOUYIN_COOKIE
    if os.path.exists(os.path.join(HERE, "cookies.txt")):
        o["cookiefile"] = os.path.join(HERE, "cookies.txt")
    o.update(extra or {})
    return o


def explain(err):
    s = str(err).lower()
    if "sign in" in s or "login" in s or "cookie" in s or "not a bot" in s:
        return Fail("NEED_LOGIN", "这个平台要求登录才能下载，服务器没法下（可以用安卓版登录后下载）")
    if "private" in s or "unavailable" in s or "not exist" in s or "removed" in s:
        return Fail("NOT_FOUND", "视频不存在、已删除或是私密的")
    if "unsupported url" in s:
        return Fail("UNSUPPORTED", "暂不支持这个网站")
    return Fail("FAILED", "解析失败：" + str(err)[-160:])


def ydl_info(url):
    import yt_dlp
    try:
        with yt_dlp.YoutubeDL(ydl_opts({"skip_download": True}, url)) as y:
            j = y.extract_info(url, download=False)
    except Exception as e:
        raise explain(e)
    if j.get("entries") and not j.get("formats"):
        j = next((e for e in j["entries"] if e), j)
    heights = sorted({f.get("height") for f in (j.get("formats") or []) if f.get("height")}, reverse=True)
    key, name = platform_of(url)
    return {"platform": key, "platformName": name, "id": j.get("id", ""),
            "title": (j.get("title") or "视频").strip()[:80], "uploader": j.get("uploader") or "",
            "duration": int(j.get("duration") or 0), "thumb": j.get("thumbnail") or "",
            "heights": heights[:6], "webpage": j.get("webpage_url") or url}


def ydl_download(url, q, folder):
    import yt_dlp
    o = {"outtmpl": os.path.join(folder, "%(title).60B.%(ext)s"), "max_filesize": MAX_BYTES,
         "concurrent_fragment_downloads": 4, "restrictfilenames": False}
    if q == "audio":
        o.update({"format": "ba/b", "postprocessors": [{"key": "FFmpegExtractAudio", "preferredcodec": "mp3"}]})
    else:
        if q.isdigit():
            o["format"] = f"bv*[height<={q}]+ba/b[height<={q}]/bv*+ba/b"
        else:
            o["format"] = "bv*+ba/b"
        o.update({"format_sort": ["res", "vcodec:h264", "acodec:m4a"], "merge_output_format": "mp4"})
    try:
        with yt_dlp.YoutubeDL(ydl_opts(o, url)) as y:
            y.download([url])
    except Exception as e:
        raise explain(e)
    files = [os.path.join(folder, f) for f in os.listdir(folder) if not f.endswith((".part", ".ytdl"))]
    files = [f for f in files if os.path.isfile(f)]
    if not files:
        raise Fail("FAILED", "没下载到文件")
    return max(files, key=os.path.getsize)


# ------------------------------------------------------------------ main logic
def info(text):
    url = resolve(extract_url(text))
    if platform_of(url)[0] == "douyin":
        d = douyin_info(url)
        if d.get("ydl"):
            d.pop("heights", None)
            d["choices"] = [{"q": "best", "label": "下载视频"}, {"q": "audio", "label": "只要声音 MP3"}]
            return d
        d["choices"] = ([{"q": "best", "label": "无水印视频"}] if d["direct"] else []) + \
                       ([{"q": "img%d" % i, "label": "图片 %d" % (i + 1)} for i in range(len(d["images"]))])
        return d
    d = ydl_info(url)
    ch = [{"q": "best", "label": "最高画质"}]
    for h in d.pop("heights"):
        if h in (1080, 720, 480) or (h < 1080 and not ch[1:]):
            ch.append({"q": str(h), "label": f"{h}p"})
    ch.append({"q": "audio", "label": "只要声音 MP3"})
    d["choices"] = ch
    return d


def safe_name(s, ext):
    s = re.sub(r"[\\/:*?\"<>|\r\n\t#]+", " ", s).strip() or "video"
    return s[:60] + ext


class Handler(BaseHTTPRequestHandler):
    server_version = "vdl/1.0"

    def log_message(self, fmt, *args):
        print("%s %s" % (self.address_string(), fmt % args), flush=True)

    def cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Expose-Headers", "Content-Disposition, Content-Length")

    def send_json(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.cors()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(b)

    def send_error_page(self, code, msg):
        b = ("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
             "<body style='font:17px -apple-system,sans-serif;padding:40px 24px;background:#111;color:#eee'>"
             "<h2>下载失败</h2><p>%s</p><p><a style='color:#5ac8fa' href='javascript:history.back()'>返回</a></p>" % msg).encode()
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_OPTIONS(self):
        self.send_response(204)
        self.cors()
        self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
        self.end_headers()

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        qs = urllib.parse.parse_qs(u.query)
        p = u.path.rstrip("/") or "/"
        try:
            if p == "/api/health":
                return self.send_json(200, {"ok": True})
            if p == "/api/debug":
                u2 = resolve(extract_url(qs.get("url", [""])[0]))
                vid = douyin_id(u2)
                _, notes = douyin_probe(vid) if vid else (None, [])
                return self.send_json(200, {"resolved": u2, "id": vid, "cookie": bool(DOUYIN_COOKIE), "tries": notes})
            if p == "/api/info":
                return self.send_json(200, {"ok": True, **info(qs.get("url", [""])[0])})
            if p == "/api/download":
                return self.download(qs.get("url", [""])[0], qs.get("q", ["best"])[0])
            if p in ("/", "/index.html", "/manifest.webmanifest", "/apple-touch-icon.png", "/icon-192.png"):
                return self.static("index.html" if p == "/" else p[1:])
            self.send_json(404, {"ok": False, "code": "NOT_FOUND", "msg": "没有这个地址"})
        except Fail as e:
            if p == "/api/download":
                return self.send_error_page(400, e)
            self.send_json(200, {"ok": False, "code": e.code, "msg": str(e)})
        except BrokenPipeError:
            pass
        except Exception as e:  # noqa
            if p == "/api/download":
                return self.send_error_page(500, "出错了：%s" % e)
            self.send_json(500, {"ok": False, "code": "ERROR", "msg": "服务器出错：%s" % e})

    def static(self, name):
        path = os.path.join(HERE, "web", name)
        if not os.path.isfile(path):
            return self.send_json(404, {"ok": False})
        ct = {"html": "text/html; charset=utf-8", "png": "image/png",
              "webmanifest": "application/manifest+json"}[name.rsplit(".", 1)[1]]
        with open(path, "rb") as f:
            b = f.read()
        self.send_response(200)
        self.send_header("Content-Type", ct)
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def attach_headers(self, name, ctype, size=None):
        self.send_response(200)
        self.cors()
        self.send_header("Content-Type", ctype)
        quoted = urllib.parse.quote(name)
        self.send_header("Content-Disposition", "attachment; filename=\"download%s\"; filename*=UTF-8''%s"
                         % (os.path.splitext(name)[1], quoted))
        if size is not None:
            self.send_header("Content-Length", str(size))
        self.end_headers()

    def download(self, text, q):
        url = resolve(extract_url(text))
        if platform_of(url)[0] == "douyin":
            d = douyin_info(url)
            if d.get("ydl"):
                url = f"https://www.douyin.com/video/{d['id']}"
            elif q.startswith("img"):
                src, ext, ctype = d["images"][int(q[3:])], ".jpg", "image/jpeg"
            else:
                src, ext, ctype = d["direct"], ".mp4", "video/mp4"
        if platform_of(url)[0] == "douyin" and not d.get("ydl"):
            if not src:
                raise Fail("NOT_FOUND", "没有可下载的内容")
            with http_get(src, referer="https://www.douyin.com/", timeout=30) as r:
                size = r.headers.get("Content-Length")
                ct = r.headers.get("Content-Type", "")
                if ct.startswith("text/") or "json" in ct:
                    raise Fail("DOUYIN_BLOCKED", "抖音没有返回视频，稍后再试")
                self.attach_headers(safe_name(d["title"], ext), ctype, size)
                shutil.copyfileobj(r, self.wfile, 256 * 1024)
            return
        folder = tempfile.mkdtemp(prefix="vdl-")
        try:
            path = ydl_download(url, q, folder)
            ext = os.path.splitext(path)[1].lower()
            ctype = {".mp4": "video/mp4", ".mp3": "audio/mpeg", ".m4a": "audio/mp4", ".webm": "video/webm"}.get(ext, "application/octet-stream")
            self.attach_headers(os.path.basename(path), ctype, os.path.getsize(path))
            with open(path, "rb") as f:
                shutil.copyfileobj(f, self.wfile, 256 * 1024)
        finally:
            shutil.rmtree(folder, ignore_errors=True)


def cleanup_loop():
    while True:  # remove leftovers of interrupted downloads
        time.sleep(600)
        tmp = tempfile.gettempdir()
        for n in os.listdir(tmp):
            pth = os.path.join(tmp, n)
            if n.startswith("vdl-") and time.time() - os.path.getmtime(pth) > 3600:
                shutil.rmtree(pth, ignore_errors=True)


if __name__ == "__main__":
    threading.Thread(target=cleanup_loop, daemon=True).start()
    print("万能下载服务器 on port %d" % PORT, flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
