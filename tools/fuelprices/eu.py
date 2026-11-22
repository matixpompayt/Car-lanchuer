#!/usr/bin/env python3
"""
Średnie ceny paliw w krajach UE → docs/fuel_eu.json (dla CAR OS Connect)

Źródło: Komisja Europejska, Weekly Oil Bulletin – „Prices with taxes, latest prices” (xlsx, co tydzień).
Ceny detaliczne z podatkami, w EUR za litr: Euro-super 95, olej napędowy, LPG.
Pobiera najwyżej raz na 12 h (plik i tak zmienia się raz w tygodniu); gdy się nie uda – zostaje poprzedni plik.
"""
import datetime as dt
import io
import json
import re
import sys
import urllib.request

OUT = sys.argv[1] if len(sys.argv) > 1 else "docs/fuel_eu.json"
PAGE = "https://energy.ec.europa.eu/data-and-analysis/weekly-oil-bulletin_en"
# stały dokument „Prices with taxes – latest” (aktualizowany co tydzień pod tym samym id)
KNOWN = "https://energy.ec.europa.eu/document/download/264c2d0f-f161-4ea3-a777-78faae59bea0_en"
UA = {"User-Agent": "Mozilla/5.0 (CarOS fuel prices; github.com/matixpompayt/Car-lanchuer)", "Accept": "*/*"}

COUNTRIES = {
    "austria": "AT", "belgium": "BE", "bulgaria": "BG", "croatia": "HR", "cyprus": "CY", "czechia": "CZ",
    "czech republic": "CZ", "denmark": "DK", "estonia": "EE", "finland": "FI", "france": "FR", "germany": "DE",
    "greece": "GR", "hungary": "HU", "ireland": "IE", "italy": "IT", "latvia": "LV", "lithuania": "LT",
    "luxembourg": "LU", "malta": "MT", "netherlands": "NL", "poland": "PL", "portugal": "PT", "romania": "RO",
    "slovakia": "SK", "slovenia": "SI", "spain": "ES", "sweden": "SE",
}


def get(url, timeout=60):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def load():
    try:
        with open(OUT, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def links():
    """Linki do xlsx „with taxes” ze strony Oil Bulletin (najpierw), potem znany stały dokument."""
    out = []
    try:
        html = get(PAGE).decode("utf-8", "replace")
        for m in re.finditer(r'href="([^"]+)"[^>]*>([^<]*)<', html):
            href, text = m.group(1), m.group(2)
            ctx = html[max(0, m.start() - 400): m.end() + 200].lower()
            if "document/download" in href and "with taxes" in ctx and "without" not in text.lower():
                if href.startswith("/"):
                    href = "https://energy.ec.europa.eu" + href
                if href not in out:
                    out.append(href)
    except Exception as e:
        print("strona Oil Bulletin:", e)
    if KNOWN not in out:
        out.append(KNOWN)
    return out


def num(v):
    if v is None:
        return None
    if isinstance(v, (int, float)):
        return float(v)
    s = str(v).strip().replace(" ", "").replace(" ", "")
    if not s:
        return None
    if "," in s and "." in s:
        s = s.replace(",", "")
    else:
        s = s.replace(",", ".")
    try:
        return float(s)
    except ValueError:
        return None


def per_litre(v):
    """Biuletyn podaje ceny za 1000 l – zamiana na litr."""
    if v is None or v <= 0:
        return None
    return round(v / 1000.0, 4) if v > 20 else round(v, 4)


def parse(data):
    import openpyxl
    wb = openpyxl.load_workbook(io.BytesIO(data), data_only=True, read_only=True)
    best = None
    for ws in wb.worksheets:
        rows = [list(r) for r in ws.iter_rows(values_only=True)]
        # wiersz nagłówka: kolumny z nazwami paliw
        col95 = colDie = colLpg = None
        hdr = -1
        for i, r in enumerate(rows[:30]):
            cells = [str(c).lower() if c is not None else "" for c in r]
            for j, c in enumerate(cells):
                if col95 is None and ("euro-super 95" in c or "eurosuper 95" in c or "euro super 95" in c):
                    col95, hdr = j, i
                if colDie is None and ("gas oil automobile" in c or "automotive gas oil" in c or "diesel" in c):
                    colDie = j
                if colLpg is None and ("lpg" in c or "gpl" in c):
                    colLpg = j
            if col95 is not None and colDie is not None:
                break
        if col95 is None or colDie is None:
            continue
        # data obowiązywania – pierwsza data w arkuszu
        date = None
        for r in rows[:hdr + 1]:
            for c in r:
                if isinstance(c, (dt.date, dt.datetime)):
                    date = (c.date() if isinstance(c, dt.datetime) else c).isoformat()
                    break
                if c is not None:
                    m = re.search(r"(\d{1,2})[./](\d{1,2})[./](\d{4})", str(c))
                    if m:
                        date = f"{m.group(3)}-{int(m.group(2)):02d}-{int(m.group(1)):02d}"
                        break
            if date:
                break
        prices = {}
        for r in rows[hdr + 1:]:
            if not r:
                continue
            iso = None
            for c in r[:3]:
                if c is None:
                    continue
                t = str(c).strip().lower()
                if t in COUNTRIES:
                    iso = COUNTRIES[t]
                elif t.upper() in COUNTRIES.values():
                    iso = t.upper()
                else:
                    iso = next((v for k, v in COUNTRIES.items() if t.startswith(k + " ") or t.endswith(" " + k)), None)
                if iso:
                    break
            if not iso:
                continue
            if iso in prices:
                continue
            p95 = per_litre(num(r[col95]) if col95 < len(r) else None)
            pdie = per_litre(num(r[colDie]) if colDie < len(r) else None)
            plpg = per_litre(num(r[colLpg]) if colLpg is not None and colLpg < len(r) else None)
            if p95 is None and pdie is None:
                continue
            # tylko sensowne ceny w EUR/l (bloki w walucie krajowej odpadają)
            prices[iso] = {k: v for k, v in (("pb95", p95), ("on", pdie), ("lpg", plpg)) if v is not None and 0.3 < v < 4.0}
            if not prices[iso]:
                del prices[iso]
        print(f"arkusz {ws.title!r}: nagłówek w wierszu {hdr}, kolumny 95={col95} ON={colDie} LPG={colLpg}, krajów {len(prices)}, data {date}")
        if len(prices) >= 20 and (best is None or len(prices) > len(best[0])):
            best = (prices, date)
    return best


def main():
    old = load()
    now = dt.datetime.now(dt.timezone.utc)
    try:
        last = dt.datetime.fromisoformat(old.get("fetched", "2000-01-01T00:00:00+00:00"))
        if (now - last).total_seconds() < 12 * 3600 and old.get("prices") and "--force" not in sys.argv:
            print("Ceny UE: świeże, pomijam")
            return
    except Exception:
        pass
    for url in links():
        try:
            data = get(url)
            if not data.startswith(b"PK"):
                print("nie xlsx:", url)
                continue
            res = parse(data)
            if not res:
                print("nie rozpoznano tabeli:", url)
                continue
            prices, date = res
            out = {
                "source": "European Commission – Weekly Oil Bulletin (prices with taxes)",
                "unit": "EUR/l",
                "date": date,
                "fetched": now.replace(microsecond=0).isoformat(),
                "prices": dict(sorted(prices.items())),
            }
            with open(OUT, "w", encoding="utf-8") as f:
                json.dump(out, f, ensure_ascii=False, indent=1)
            print(f"Ceny UE: {len(prices)} krajów, {date}, PL={prices.get('PL')}, DE={prices.get('DE')}")
            return
        except Exception as e:
            print("błąd", url, e)
    print("Ceny UE: nie udało się – zostaje poprzedni plik")


if __name__ == "__main__":
    main()
