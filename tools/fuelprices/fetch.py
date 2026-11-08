#!/usr/bin/env python3
"""
Ceny paliw dla CAR OS / CAR OS Connect → docs/fuel.json

- Pb95, Pb98, ON: maksymalne ceny z obwieszczeń Ministra Energii w Monitorze Polskim
  (API Sejmu ELI: wyszukiwanie aktów MP z tytułem „maksymalnej ceny paliw”, tekst z PDF).
  Cena obowiązuje od dnia następującego po ogłoszeniu.
- LPG: średnia detaliczna z serwisów cenowych (próbuje kilka źródeł; gdy się nie uda – zostaje ostatnia).

Plik trzyma historię (ok. 120 dni), więc przy każdym uruchomieniu pobiera tylko nowe obwieszczenia.
"""
import datetime as dt
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request

OUT = sys.argv[1] if len(sys.argv) > 1 else "docs/fuel.json"
API = "https://api.sejm.gov.pl/eli"
UA = {"User-Agent": "Mozilla/5.0 (CarOS fuel prices; github.com/matixpompayt/Car-lanchuer)", "Accept": "*/*"}
KEEP_DAYS = 120


def get(url, timeout=60):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def load():
    try:
        with open(OUT, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {"history": [], "lpgHistory": []}


def search_acts(year):
    q = urllib.parse.urlencode({"publisher": "MP", "year": year, "title": "maksymalnej ceny paliw", "limit": 500})
    data = json.loads(get(f"{API}/acts/search?{q}"))
    items = data.get("items", data if isinstance(data, list) else [])
    out = []
    for it in items:
        title = (it.get("title") or "").lower()
        if "maksymaln" not in title or "paliw" not in title:
            continue
        out.append(it)
    return out


def pdf_text(eli):
    raw = get(f"{API}/acts/{eli}/text.pdf")
    with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as f:
        f.write(raw)
        path = f.name
    try:
        return subprocess.run(["pdftotext", "-layout", path, "-"], capture_output=True, text=True, timeout=60).stdout
    finally:
        os.unlink(path)


def parse_prices(text):
    """Z tekstu obwieszczenia: pary (netto, z VAT) w kolejności tabeli: Pb95, Pb98, ON."""
    nums = [float(a + "." + b) for a, b in re.findall(r"(?<![\d,])(\d{1,2}),(\d{2})(?![\d,])", text)]
    pairs = []
    i = 0
    while i < len(nums) - 1:
        a, b = nums[i], nums[i + 1]
        if 3.0 < a < 15.0 and 1.04 < b / a < 1.30:
            pairs.append(b)
            i += 2
        else:
            i += 1
    if len(pairs) < 3:
        return None
    pb95, pb98, on = pairs[0], pairs[1], pairs[2]
    # sprawdź kolejność po słowach kluczowych (gdyby tabela była inna)
    low = text.lower()
    p95, p98, pon = low.find(" 95"), low.find(" 98"), low.find("napędow")
    if p95 >= 0 and p98 >= 0 and pon >= 0 and not (p95 < p98 < pon):
        order = sorted([(p95, "pb95"), (p98, "pb98"), (pon, "on")])
        vals = dict(zip([k for _, k in order], pairs[:3]))
        pb95, pb98, on = vals["pb95"], vals["pb98"], vals["on"]
    return {"pb95": pb95, "pb98": pb98, "on": on}


def update_max(data):
    hist = {h["eli"]: h for h in data.get("history", []) if "eli" in h}
    today = dt.date.today()
    found = 0
    for year in sorted({today.year - 1, today.year}):
        try:
            acts = search_acts(year)
        except Exception as e:
            print(f"API Sejmu {year}: {e}")
            continue
        for it in acts:
            eli = it.get("ELI") or f"MP/{year}/{it.get('pos')}"
            prom = it.get("promulgation") or it.get("announcementDate")
            if not prom:
                continue
            d = dt.date.fromisoformat(prom[:10])
            if (today - d).days > KEEP_DAYS:
                continue
            found += 1
            if eli in hist:
                continue
            try:
                p = parse_prices(pdf_text(eli))
            except Exception as e:
                print(f"{eli}: błąd PDF {e}")
                continue
            if not p:
                print(f"{eli}: nie znalazłem cen")
                continue
            p.update({"eli": eli, "published": d.isoformat(), "from": (d + dt.timedelta(days=1)).isoformat()})
            hist[eli] = p
            print(f"{eli} ({d}): Pb95 {p['pb95']} Pb98 {p['pb98']} ON {p['on']}")
    # jedna pozycja na dzień obowiązywania (późniejsze obwieszczenie wygrywa)
    by_from = {}
    for h in sorted(hist.values(), key=lambda x: (x["from"], x["eli"])):
        by_from[h["from"]] = h
    cutoff = (today - dt.timedelta(days=KEEP_DAYS)).isoformat()
    data["history"] = [h for h in sorted(by_from.values(), key=lambda x: x["from"]) if h["from"] >= cutoff]
    print(f"Obwieszczeń w oknie: {found}, w historii: {len(data['history'])}")


LPG_SOURCES = [
    "https://www.e-petrol.pl/notowania/rynek-krajowy/ceny-stacje-paliw",
    "https://www.autocentrum.pl/paliwa/ceny-paliw/",
    "https://zapaliwo.pl/",
]


def find_lpg(html):
    text = re.sub(r"<[^>]+>", " ", html)
    text = re.sub(r"&nbsp;|\s+", " ", text)
    for m in re.finditer(r"(?:LPG|Autogaz|autogaz)", text):
        seg = text[m.end(): m.end() + 160]
        for n in re.findall(r"(\d)[,.](\d{2})", seg):
            v = float(n[0] + "." + n[1])
            if 2.0 <= v <= 5.5:
                return v
    return None


def update_lpg(data):
    today = dt.date.today().isoformat()
    for url in LPG_SOURCES:
        try:
            v = find_lpg(get(url, 40).decode("utf-8", "ignore"))
        except Exception as e:
            print(f"LPG {url}: {e}")
            continue
        if v is None:
            print(f"LPG {url}: brak ceny")
            continue
        print(f"LPG {url}: {v}")
        h = [x for x in data.get("lpgHistory", []) if x["date"] != today]
        h.append({"date": today, "price": v, "src": urllib.parse.urlparse(url).netloc})
        cutoff = (dt.date.today() - dt.timedelta(days=KEEP_DAYS)).isoformat()
        data["lpgHistory"] = [x for x in sorted(h, key=lambda x: x["date"]) if x["date"] >= cutoff]
        return
    print("LPG: żadne źródło nie zadziałało – zostaje ostatnia cena")


def main():
    data = load()
    before = json.dumps({k: data.get(k) for k in ("history", "lpgHistory")}, sort_keys=True)
    update_max(data)
    update_lpg(data)
    after = json.dumps({k: data.get(k) for k in ("history", "lpgHistory")}, sort_keys=True)
    data["source"] = "Ceny maksymalne: obwieszczenia Ministra Energii (Monitor Polski). LPG: średnia detaliczna z serwisów cenowych."
    if before != after or "updated" not in data:
        data["updated"] = dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")
    os.makedirs(os.path.dirname(OUT) or ".", exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    print("zmiany" if before != after else "bez zmian")


if __name__ == "__main__":
    main()
