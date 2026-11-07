"""Builds the travel app's data: Wikipedia coordinates and photos (with credits), road times between regions.

Run in GitHub Actions (needs internet): python travel-data/build.py
Writes travel/src/main/assets/data.js and travel/src/main/assets/img/*, plus contact-sheet.jpg for review.
"""
import io, json, math, os, re, sys, time, urllib.parse, urllib.request
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(__file__))
import places as P  # noqa: E402

UA = "TravelGuideBuilder/1.0 (https://github.com/mronexie-cloud/GitHub; educational travel app)"
ROOT = os.path.join(os.path.dirname(__file__), "..")
ASSETS = os.path.join(ROOT, "travel", "src", "main", "assets")
IMG = os.path.join(ASSETS, "img")
os.makedirs(IMG, exist_ok=True)
SKIP_IMG = re.compile(r"map|locator|logo|flag|coat|seal|icon|diagram|plan|svg|emblem|location|chart|sign|commons-logo|wiki", re.I)


def get(url, binary=False, tries=4):
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=60) as r:
                data = r.read()
            return data if binary else json.loads(data.decode("utf-8"))
        except Exception as e:
            print("  retry", i, url[:120], e, flush=True)
            time.sleep(3 + i * 4)
    return None


def km(a, b, c, d):
    r = math.radians
    x = math.sin(r(c - a) / 2) ** 2 + math.cos(r(a)) * math.cos(r(c)) * math.sin(r(d - b) / 2) ** 2
    return 12742 * math.asin(math.sqrt(x))


def wiki_pages(titles):
    """title -> {lat, lng, image (file name)} using the English Wikipedia API, following redirects."""
    out = {}
    titles = sorted(set(titles))
    for i in range(0, len(titles), 40):
        chunk = titles[i:i + 40]
        q = urllib.parse.urlencode({"action": "query", "format": "json", "redirects": 1, "prop": "pageimages|coordinates|images",
                                    "piprop": "name", "imlimit": "max", "titles": "|".join(chunk)})
        d = get("https://en.wikipedia.org/w/api.php?" + q)
        if not d:
            continue
        q_ = d.get("query", {})
        back = {t: t for t in chunk}
        for n in q_.get("normalized", []):
            back[n["to"]] = n["from"]
        for r in q_.get("redirects", []):
            back[r["to"]] = back.get(r["from"], r["from"])
        for p in q_.get("pages", {}).values():
            t = back.get(p.get("title"), p.get("title"))
            c = (p.get("coordinates") or [{}])[0]
            imgs = [x["title"][5:] for x in p.get("images", []) if x["title"].lower().endswith((".jpg", ".jpeg"))]
            out[t] = {"lat": c.get("lat"), "lng": c.get("lon"), "image": p.get("pageimage"), "images": imgs, "missing": "missing" in p}
        time.sleep(1)
    return out


def file_info(names, width):
    """Commons file name -> {url (thumbnail), w, h, artist, license, page}."""
    out = {}
    names = sorted(set(n for n in names if n))
    for i in range(0, len(names), 40):
        chunk = names[i:i + 40]
        q = urllib.parse.urlencode({"action": "query", "format": "json", "prop": "imageinfo", "iiprop": "url|size|extmetadata",
                                    "iiurlwidth": width, "titles": "|".join("File:" + n for n in chunk)})
        d = get("https://commons.wikimedia.org/w/api.php?" + q)
        if not d:
            continue
        norm = {n["to"]: n["from"] for n in d.get("query", {}).get("normalized", [])}
        for p in d.get("query", {}).get("pages", {}).values():
            ii = (p.get("imageinfo") or [None])[0]
            if not ii:
                continue
            title = norm.get(p["title"], p["title"])[5:]
            md = ii.get("extmetadata", {})
            artist = re.sub(r"<[^>]+>", "", md.get("Artist", {}).get("value", "")).strip()
            out[title.replace(" ", "_")] = out[title] = {
                "url": ii.get("thumburl") or ii.get("url"), "w": ii.get("width"), "h": ii.get("height"),
                "artist": artist[:80], "license": md.get("LicenseShortName", {}).get("value", ""),
                "page": ii.get("descriptionurl", "")}
        time.sleep(1)
    return out


def save_photo(url, pid):
    raw = get(url, binary=True)
    if not raw:
        return False
    im = Image.open(io.BytesIO(raw)).convert("RGB")
    big = im.copy()
    big.thumbnail((960, 960))
    big.save(os.path.join(IMG, pid + ".jpg"), "JPEG", quality=78, optimize=True, progressive=True)
    small = im.copy()
    small.thumbnail((360, 360))
    small.save(os.path.join(IMG, pid + "_s.jpg"), "JPEG", quality=72, optimize=True)
    return True


def main():
    regions = [dict(key=r[0], country=r[1], name=r[2], lat=r[3], lng=r[4], airport=r[5], group=r[6],
                    ferry=None if not r[7] else dict(name=r[7][0], lat=r[7][1], lng=r[7][2], hours=r[7][3])) for r in P.REGIONS]
    rkeys = {r["key"] for r in regions}
    places = [dict(p) for p in P.PLACES]
    for p in places:
        assert p["region"] in rkeys, p["id"]
    print(len(regions), "regions,", len(places), "places", flush=True)

    # 1) Wikipedia: coordinates + lead photo + other photos of each article
    pages = wiki_pages([p["wiki"] for p in places if p.get("wiki")])
    lead_names, extra_names = [], []
    for p in places:
        w = pages.get(p["wiki"]) or {}
        p["_w"] = w
        if w.get("lat") is not None and km(p["lat"], p["lng"], w["lat"], w["lng"]) < 20:
            p["lat"], p["lng"] = round(w["lat"], 5), round(w["lng"], 5)
        elif w.get("lat") is not None:
            print("  coords differ for", p["id"], round(km(p["lat"], p["lng"], w["lat"], w["lng"])), "km - keeping ours")
        if w.get("image") and not SKIP_IMG.search(w["image"]):
            lead_names.append(w["image"])
        extra_names += [n for n in (w.get("images") or [])[:25] if not SKIP_IMG.search(n)]
    lead = file_info(lead_names, 1280)
    extra = file_info([n for n in extra_names if n not in lead], 800)

    # 2) photos: the lead image is stored with the app; up to two more are linked from Wikimedia
    sheet = []
    for p in places:
        w = p.pop("_w")
        name = w.get("image")
        info = lead.get(name) if name else None
        p["photos"] = []
        if info and info.get("url"):
            if save_photo(info["url"], p["id"]):
                p["img"] = "img/%s.jpg" % p["id"]
                p["thumb"] = "img/%s_s.jpg" % p["id"]
                p["credit"] = {"artist": info["artist"], "license": info["license"], "page": info["page"]}
                sheet.append(p["id"])
        for n in (w.get("images") or []):
            if len(p["photos"]) >= 2:
                break
            e = extra.get(n)
            if not e or n == name or not e.get("w") or e["w"] < 1000 or not e.get("h"):
                continue
            ratio = e["w"] / e["h"]
            if 1.2 <= ratio <= 2.0:
                p["photos"].append({"url": e["url"], "artist": e["artist"], "license": e["license"], "page": e["page"]})
        time.sleep(0.3)
    print("photos stored:", len(sheet), "of", len(places), flush=True)
    missing = [p["id"] for p in places if not p.get("img")]
    print("no photo:", missing, flush=True)

    # 3) road times between region centres and ferry piers (OSRM public server; straight-line estimate as fallback)
    pts = [(r["key"], r["lat"], r["lng"]) for r in regions] + [("ferry:" + r["key"], r["ferry"]["lat"], r["ferry"]["lng"]) for r in regions if r["ferry"]]
    coords = ";".join("%.5f,%.5f" % (lng, lat) for _, lat, lng in pts)
    m = get("https://router.project-osrm.org/table/v1/driving/" + coords + "?annotations=duration,distance")
    matrix = {"ids": [k for k, _, _ in pts], "min": None, "km": None, "src": "OSRM"}
    if m and m.get("code") == "Ok":
        matrix["min"] = [[None if v is None else round(v / 60) for v in row] for row in m["durations"]]
        matrix["km"] = [[None if v is None else round(v / 1000) for v in row] for row in m["distances"]]
        print("OSRM matrix ok", len(pts), flush=True)
    else:
        print("OSRM failed; the app will estimate from straight-line distance", flush=True)

    data = {"built": time.strftime("%Y-%m-%d"), "regions": regions, "places": places,
            "routes": [dict(id=r[0], name=r[1], start=r[2], note=r[3], days=r[4]) for r in P.ROUTES],
            "towns": [dict(name=t[0], lat=t[1], lng=t[2]) for t in P.TOWNS], "fx": P.FX, "notes": P.NOTES, "matrix": matrix}
    ids = {p["id"] for p in places}
    for r in data["routes"]:
        for day in r["days"]:
            for pid in day:
                assert pid in ids, (r["id"], pid)
    with open(os.path.join(ASSETS, "data.js"), "w", encoding="utf-8") as f:
        f.write("window.DATA=" + json.dumps(data, ensure_ascii=False, separators=(",", ":")) + ";\n")

    # 4) contact sheet for checking the photos by eye
    cols, cw, ch = 8, 240, 190
    rows = math.ceil(len(places) / cols)
    cs = Image.new("RGB", (cols * cw, rows * ch), "white")
    d = ImageDraw.Draw(cs)
    for i, p in enumerate(places):
        x, y = (i % cols) * cw, (i // cols) * ch
        if p.get("thumb"):
            im = Image.open(os.path.join(ASSETS, p["thumb"]))
            im.thumbnail((cw - 8, ch - 30))
            cs.paste(im, (x + 4, y + 4))
        d.text((x + 4, y + ch - 22), p["id"], fill="black")
    cs.save("contact-sheet.jpg", quality=80)
    print("done", flush=True)


if __name__ == "__main__":
    main()
