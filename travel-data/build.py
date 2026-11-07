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


def api_all(base, params):
    """MediaWiki query with continuation; merges page props across batches."""
    pages, extra = {}, {}
    cont = {}
    for _ in range(30):
        q = dict(params, action="query", format="json", **cont)
        d = get(base + "?" + urllib.parse.urlencode(q))
        if not d:
            break
        qq = d.get("query", {})
        for k in ("normalized", "redirects"):
            extra.setdefault(k, []).extend(qq.get(k, []))
        for pid, pg in qq.get("pages", {}).items():
            cur = pages.setdefault(pid, {})
            for k, v in pg.items():
                if isinstance(v, list):
                    cur.setdefault(k, []).extend(v)
                else:
                    cur[k] = v
        if "continue" not in d:
            break
        cont = d["continue"]
        time.sleep(0.5)
    return pages, extra


def wiki_pages(titles):
    """title -> {lat, lng, image (lead file name), images (all .jpg files in the article)} from English Wikipedia."""
    out = {}
    titles = sorted(set(titles))
    for i in range(0, len(titles), 20):
        chunk = titles[i:i + 20]
        pages, ex = api_all("https://en.wikipedia.org/w/api.php", {
            "redirects": 1, "prop": "pageimages|coordinates|images", "piprop": "name", "pilimit": "max",
            "colimit": "max", "imlimit": "max", "titles": "|".join(chunk)})
        back = {t: t for t in chunk}
        for n in ex.get("normalized", []):
            back[n["to"]] = back.get(n["from"], n["from"])
        for r in ex.get("redirects", []):
            back[r["to"]] = back.get(r["from"], r["from"])
        for p in pages.values():
            t = back.get(p.get("title"), p.get("title"))
            c = (p.get("coordinates") or [{}])[0]
            imgs = [x["title"][5:] for x in p.get("images", []) if x["title"].lower().endswith((".jpg", ".jpeg"))]
            out[t] = {"lat": c.get("lat"), "lng": c.get("lon"), "image": p.get("pageimage"), "images": imgs, "missing": "missing" in p}
        time.sleep(1)
    return out


def commons_search(query, n=12):
    """Commons file search -> list of file infos (landscape-ish bitmaps first)."""
    pages, _ = api_all("https://commons.wikimedia.org/w/api.php", {
        "generator": "search", "gsrsearch": query + " filetype:bitmap", "gsrnamespace": 6, "gsrlimit": n,
        "prop": "imageinfo", "iiprop": "url|size|extmetadata", "iiurlwidth": 1280})
    res = []
    for p in sorted(pages.values(), key=lambda x: x.get("index", 99)):
        ii = (p.get("imageinfo") or [None])[0]
        if not ii or SKIP_IMG.search(p["title"]):
            continue
        md = ii.get("extmetadata", {})
        res.append({"name": p["title"][5:], "url": ii.get("thumburl") or ii.get("url"), "w": ii.get("width"), "h": ii.get("height"),
                    "artist": re.sub(r"<[^>]+>", "", md.get("Artist", {}).get("value", "")).strip()[:80],
                    "license": md.get("LicenseShortName", {}).get("value", ""), "page": ii.get("descriptionurl", "")})
    return res


def good_shape(e, minw=900):
    return e and e.get("url") and e.get("w") and e.get("h") and e["w"] >= minw and 1.15 <= e["w"] / e["h"] <= 2.1


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
    # 1b) places without a Wikipedia article: position from OpenStreetMap search (only if close to our estimate)
    for p in places:
        q = p.pop("geo", None)
        if not q or p["_w"].get("lat") is not None:
            continue
        d = get("https://nominatim.openstreetmap.org/search?" + urllib.parse.urlencode({"q": q, "format": "json", "limit": 1}))
        time.sleep(1.2)
        if d:
            lat, lng = float(d[0]["lat"]), float(d[0]["lon"])
            dist = km(p["lat"], p["lng"], lat, lng)
            if dist < 2.5:
                print("  geo %-14s moved %.2f km  (%s)" % (p["id"], dist, d[0].get("display_name", "")[:60]), flush=True)
                p["lat"], p["lng"] = round(lat, 5), round(lng, 5)
            else:
                print("  geo %-14s too far (%d km) - keeping ours" % (p["id"], dist), flush=True)
        else:
            print("  geo %-14s not found" % p["id"], flush=True)
    lead = file_info(lead_names, 1280)
    extra = file_info(extra_names, 960)

    # 2) photos: the lead image is stored with the app; up to two more are linked from Wikimedia.
    #    Order: Commons search override (places.PHOTO) -> article lead image -> another article photo -> Commons search by name.
    sheet, taken = [], set()
    for f in os.listdir(IMG):
        os.remove(os.path.join(IMG, f))
    for p in places:
        w = p.pop("_w")
        name = w.get("image")
        chosen, why = None, ""
        skip = P.PHOTO.get(p["id"]) == ""
        if p["id"] in P.PHOTO and not skip:
            for q in P.PHOTO[p["id"]].split("|"):  # alternatives, best first
                cands = [e for e in commons_search(q) if good_shape(e) and e["name"] not in taken]
                if cands:
                    chosen, why = cands[0], "search:" + q
                    break
        if skip:
            w = {}
        if not chosen and not skip and name and not SKIP_IMG.search(name) and lead.get(name, {}).get("url"):
            chosen, why = dict(lead[name], name=name), "lead"
        if not chosen and not skip:
            for n in (w.get("images") or []):
                e = extra.get(n)
                if good_shape(e, 1000):
                    chosen, why = dict(e, name=n), "article"
                    break
        if not chosen and not skip:
            cands = [e for e in commons_search(p["en"]) if good_shape(e) and e["name"] not in taken]
            if cands:
                chosen, why = cands[0], "search:" + p["en"]
        p["photos"] = []
        if chosen and save_photo(chosen["url"], p["id"]):
            p["img"] = "img/%s.jpg" % p["id"]
            p["thumb"] = "img/%s_s.jpg" % p["id"]
            p["credit"] = {"artist": chosen["artist"], "license": chosen["license"], "page": chosen["page"]}
            sheet.append(p["id"])
            taken.add(chosen.get("name"))
            print("  photo %-14s %-8s %s" % (p["id"], why.split(":")[0], chosen.get("name", "")[:90]), flush=True)
        else:
            print("  photo %-14s NONE lead=%s images=%d missing=%s" % (p["id"], name, len(w.get("images") or []), w.get("missing")), flush=True)
        used = chosen.get("name") if chosen else None
        for n in (w.get("images") or []):
            if len(p["photos"]) >= 2:
                break
            e = extra.get(n) or lead.get(n)
            if n == used or not good_shape(e, 1000):
                continue
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
