"""Builds the home screen's built-in picture collection (assets/walls/).

  python3 -I tools/walls.py candidates landscapes|cities|space|art|nasa
  python3 -I tools/walls.py fetch tools/walls-picks.tsv

candidates prints pictures that fit the screen, with the ref to put in a picks file.
fetch reads lines of  category<TAB>source<TAB>ref  (source: wikimedia, met or nasa; ref: a Commons
page id, a Met object id or a NASA id), downloads each, shrinks it to 1280 px on its longest side into assets/walls/ and
writes assets/walls/index.tsv in the Picture.toTsv format the app reads.
"""
import json
import os
import re
import subprocess
import sys
import urllib.parse
import urllib.request

UA = "tvhome/1.5 (Android TV home screen; https://github.com/Ansub/tv-detox)"
CATS = {"landscapes": "Featured_pictures_of_landscapes", "cities": "Featured_pictures_of_cityscapes",
        "space": "Featured_pictures_of_astronomy"}
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "assets", "walls")


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


def fits(w, h):
    return w > 0 and h > 0 and 1.45 <= w / h <= 1.9


def plain(html):
    return re.sub(r"\s+", " ", re.sub(r"<[^>]*>", " ", html or "").replace("&amp;", "&")).strip()


def wiki_info(params):
    q = {"action": "query", "prop": "imageinfo", "iiprop": "size|url|extmetadata", "iiurlwidth": "1280",
         "iiextmetadatafilter": "LicenseShortName|Artist|ObjectName", "format": "json"}
    q.update(params)
    data = json.loads(get("https://commons.wikimedia.org/w/api.php?" + urllib.parse.urlencode(q)))
    return data.get("query", {}).get("pages", {}).values()


def wiki_fields(page):
    ii = page["imageinfo"][0]
    meta = ii.get("extmetadata", {})
    lic = plain(meta.get("LicenseShortName", {}).get("value", ""))
    title = plain(meta.get("ObjectName", {}).get("value", "")) or re.sub(r"\.[^.]+$", "", page["title"][5:])
    return ii, lic, title, plain(meta.get("Artist", {}).get("value", ""))


def ok_licence(lic):
    return lic and "NC" not in lic and "ND" not in lic


def candidates(kind):
    if kind in CATS:
        pages = wiki_info({"generator": "categorymembers", "gcmtitle": "Category:" + CATS[kind], "gcmtype": "file",
                           "gcmlimit": "100"})
        for p in pages:
            if "imageinfo" not in p:
                continue
            ii, lic, title, _ = wiki_fields(p)
            if fits(ii["width"], ii["height"]) and ok_licence(lic):
                print(f"{kind}\twikimedia\t{p['pageid']}\t# {title[:70]} ({ii['width']}x{ii['height']}, {lic})")
    elif kind == "art":
        for q in ["landscape", "seascape", "mountains", "garden", "city view"]:
            ids = json.loads(get("https://collectionapi.metmuseum.org/public/collection/v1.1/search?hasImages=true"
                                 "&isPublicDomain=true&limit=15&q=" + urllib.parse.quote_plus(q))).get("objectIDs") or []
            for i in ids:
                o = json.loads(get(f"https://collectionapi.metmuseum.org/public/collection/v1/objects/{i}"))
                if o.get("isPublicDomain") and o.get("primaryImageSmall") and o.get("classification") == "Paintings":
                    print(f"art\tmet\t{i}\t# {o['title'][:60]} - {o.get('artistDisplayName', '')}")
    elif kind == "nasa":
        for q in ["nebula", "galaxy", "earth from space", "hubble"]:
            items = json.loads(get("https://images-api.nasa.gov/search?media_type=image&q=" + urllib.parse.quote_plus(q)))
            for it in items["collection"]["items"][:25]:
                nid = it["data"][0]["nasa_id"]
                try:
                    meta = json.loads(get(f"https://images-assets.nasa.gov/image/{nid}/metadata.json"))
                except Exception:
                    continue
                w, h = meta.get("File:ImageWidth", 0), meta.get("File:ImageHeight", 0)
                if fits(w, h):
                    print(f"space\tnasa\t{nid}\t# {it['data'][0]['title'][:60]} ({w}x{h})")


def fetch(picks):
    os.makedirs(OUT, exist_ok=True)
    rows, counts = [], {}
    for line in open(picks, encoding="utf-8"):
        line = line.split("#")[0].strip()
        if not line:
            continue
        cat, source, ref = line.split("\t")[:3]
        counts[cat] = counts.get(cat, 0) + 1
        name = f"{cat[0]}{counts[cat]:02d}.jpg"
        if source == "wikimedia":
            page = next(iter(wiki_info({"pageids": ref})))
            ii, lic, title, author = wiki_fields(page)
            url, art, src, pid = ii["thumburl"], False, "Wikimedia Commons", "wm:" + ref
        elif source == "met":
            o = json.loads(get(f"https://collectionapi.metmuseum.org/public/collection/v1/objects/{ref}"))
            url, title, author, lic = o["primaryImage"] or o["primaryImageSmall"], plain(o["title"]), plain(o.get("artistDisplayName", "")), "Public domain"
            art, src, pid = True, "The Met", "met:" + ref
        else:
            meta = json.loads(get(f"https://images-api.nasa.gov/search?nasa_id={urllib.parse.quote(ref)}"))
            d = meta["collection"]["items"][0]["data"][0]
            hrefs = [i["href"] for i in json.loads(get(f"https://images-api.nasa.gov/asset/{urllib.parse.quote(ref)}"))["collection"]["items"]]
            url = next((h for size in ("~large.jpg", "~medium.jpg", "~orig.jpg") for h in hrefs if h.endswith(size)), None)
            if url is None:
                print("skipped", ref, "(no jpg)")
                continue
            url = url.replace("http://", "https://").replace(" ", "%20")
            title, author, lic, art, src, pid = plain(d["title"]), "", "Public domain", False, "NASA", "nasa:" + ref
        path = os.path.join(OUT, name)
        with open(path, "wb") as f:
            f.write(get(url))
        # Keep the longest side at 1280 px: the TV draws at 1280x720 (macOS sips; elsewhere left as is).
        subprocess.run(["sips", "-Z", "1280", path], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        fields = [pid, name, title, author, lic, src, cat, "1" if art else "0", "0", "0"]
        rows.append("\t".join(x.replace("\t", " ").replace("\n", " ") for x in fields))
        print(name, title[:60])
    with open(os.path.join(OUT, "index.tsv"), "w", encoding="utf-8") as f:
        f.write("\n".join(rows) + "\n")


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "candidates":
        candidates(sys.argv[2])
    elif len(sys.argv) == 3 and sys.argv[1] == "fetch":
        fetch(sys.argv[2])
    else:
        print(__doc__)
        sys.exit(2)
