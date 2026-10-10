"""Genera tokens.json.gz: índice compacto de tokens/emblemas/reminders de Scryfall.

Usa el bulk "default_cards" (jsonl.gz) con el mismo filtro que Scryfall.BASE en la app.
Los ids de reminder salen de la búsqueda otag:reminder-card, porque los tags no vienen en el bulk.
Solo librería estándar. Uso: python scripts/build_index.py salida/tokens.json.gz
"""
import gzip
import json
import re
import sys
import time
import urllib.parse
import urllib.request

UA = {"User-Agent": "TokenPrinter/1.0", "Accept": "application/json;q=0.9,*/*;q=0.8"}
BASE = ("(type:token or type:emblem or type:card) -layout:art_series -layout:front_card -is:minigame "
        "-set:wc97 -set:wc98 -set:wc99 -set:wc00 -set:wc01 -set:wc02 -set:wc03 -set:wc04")
WC_SETS = {"wc97", "wc98", "wc99", "wc00", "wc01", "wc02", "wc03", "wc04"}
IMG = re.compile(r"^https://cards\.scryfall\.io/(small|normal|large)/(.+)$")


def get(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120)


def search_ids(q):
    url = "https://api.scryfall.com/cards/search?unique=prints&q=" + urllib.parse.quote(q)
    ids = set()
    while url:
        d = json.load(get(url))
        ids |= {c["id"] for c in d["data"]}
        url = d.get("next_page")
        time.sleep(0.12)
    return ids


def matches(c):
    """Equivalente a BASE (validado: mismo conjunto de ids que la búsqueda de Scryfall)."""
    types = " ".join([c.get("type_line", "")] + [f.get("type_line", "") for f in c.get("card_faces", [])])
    return (re.search(r"\b(token|emblem|card)\b", types, re.I)
            and c["layout"] not in ("art_series", "front_card")
            and c.get("set_type") != "minigame"
            and c["set"] not in WC_SETS)


def image(uris):
    """small/normal comparten la misma ruta en el CDN: se guarda solo esa ruta ("u")."""
    small = uris.get("small")
    full = uris.get("normal") or uris.get("large") or small
    if not full:
        return None
    small = small or full
    ms, mf = IMG.match(small), IMG.match(full)
    if ms and mf and ms.group(2) == mf.group(2) and ms.group(1) == "small" and mf.group(1) == "normal":
        return {"u": mf.group(2)}
    return {"th": small, "fu": full}


def is_reminder_type(t):
    return t.strip().lower() == "card"


def entries(c, reminder_ids):
    """Misma lógica que Scryfall.entries() en la app: una entrada por cara con imagen propia."""
    identity = "".join(c.get("color_identity", []))
    faces = c.get("card_faces") or []
    if len(faces) > 1 and all("image_uris" in f for f in faces):
        out = []
        for f in faces:
            t = f.get("type_line", "")
            e = {"n": f.get("name", ""), "c": "".join(f["colors"]) if "colors" in f else identity}
            if "emblem" in t.lower():
                e["e"] = 1
            if is_reminder_type(t):
                e["m"] = 1
            img = image(f["image_uris"])
            if img:
                out.append({**e, **img})
        return out
    uris = c.get("image_uris") or (faces[0].get("image_uris") if faces else None)
    img = image(uris) if uris else None
    if not img:
        return []
    e = {"n": c["name"], "c": identity}
    if "emblem" in c.get("type_line", "").lower():
        e["e"] = 1
    if is_reminder_type(c.get("type_line", "")) or c["id"] in reminder_ids:
        e["m"] = 1
    return [{**e, **img}]


def main(out_path):
    reminder_ids = search_ids(BASE + " otag:reminder-card")
    bulk = json.load(get("https://api.scryfall.com/bulk-data/default-cards"))
    cards = []
    with gzip.open(get(bulk["jsonl_download_uri"]), "rt", encoding="utf-8") as f:
        for line in f:
            c = json.loads(line)
            if not matches(c):
                continue
            es = entries(c, reminder_ids)
            if es:
                cards.append({"id": c["id"], "n": c["name"], "t": c.get("type_line", ""),
                              "s": c["set_name"], "r": c["released_at"], "f": es})
    if len(cards) < 1000:
        sys.exit(f"Muy pocos tokens ({len(cards)}): algo cambió en el bulk, no se publica.")
    cards.sort(key=lambda c: (c["n"].lower(), c["r"]))
    newest = max(cards, key=lambda c: c["r"])
    index = {"v": 1, "updated": bulk["updated_at"], "total": len(cards),
             "newest": {"s": newest["s"], "r": newest["r"]}, "cards": cards}
    data = json.dumps(index, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    with gzip.open(out_path, "wb", compresslevel=9) as f:
        f.write(data)
    print(f"{len(cards)} cartas, {len(reminder_ids)} reminders, {len(data)} bytes JSON")


if __name__ == "__main__":
    main(sys.argv[1])
