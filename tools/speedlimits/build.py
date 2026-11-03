#!/usr/bin/env python3
"""
Buduje plik ograniczeń prędkości offline dla CAR OS z wycinków OSM (Geofabrik .pbf).

Użycie: build.py out.bin "Opis regionów" plik1.pbf:PL plik2.pbf:DE ...

Format (big endian, czytany w SpeedLimitRepository/OfflineSpeed.kt):
  "CSL1"  int64 czas_budowy_ms  utf(regiony)  int32 liczba_kafli
  indeks: [int32 tx, int32 ty, int32 offset, int32 długość] * n   (offset od początku pliku)
  kafle: zlib( varint ways, [varint id, u8 limit(0=brak), u8 flagi(1=wg przepisów), utf name,
                             varint n, (zz dlat, zz dlon)*n],   (1e-5°, delta od początku kafla / poprzedniego punktu)
               varint signs, [u8 kind, zz dlat, zz dlon] )
  utf = varint długość + bajty UTF-8
"""
import math
import re
import struct
import sys
import time
import zlib

import osmium

TILE = 0.05          # kafel 0.05° (~5.5 km x 3.4 km)
SCALE = 100_000      # 1e-5° ≈ 1 m
HW = re.compile(r"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|road)(_link)?$")
SIGN_KINDS = {"signals": 1, "stop": 2, "give_way": 3, "crossing": 4, "rail": 5, "camera": 6}
PLACE_R = {"city": 3500.0, "town": 1800.0, "suburb": 1200.0, "quarter": 1200.0, "village": 700.0, "hamlet": 300.0}


def dist(a_lat, a_lon, b_lat, b_lon):
    kx = 111_320.0 * math.cos(math.radians(a_lat))
    return math.hypot((a_lon - b_lon) * kx, (a_lat - b_lat) * 110_540.0)


def parse_limit(v):
    if not v:
        return None
    s = v.strip()
    if s.isdigit():
        return int(s)
    if s.endswith("mph"):
        try:
            return int(int(s[:-3].strip()) * 1.609)
        except ValueError:
            return None
    u = s.upper()
    table = {
        "PL:URBAN": 50, "PL:RURAL": 90, "PL:EXPRESSWAY": 120, "PL:MOTORWAY": 140,
        "PL:LIVING_STREET": 20, "PL:ZONE20": 20, "PL:ZONE30": 30,
        "DE:URBAN": 50, "DE:RURAL": 100, "DE:LIVING_STREET": 7, "DE:ZONE30": 30, "DE:ZONE20": 20,
    }
    if u in table:
        return table[u]
    if u in ("NONE", "SIGNALS", "WALK"):
        return None
    m = re.search(r"(\d{2,3})", s)
    return int(m.group(1)) if m else None


def exact_limit(t):
    for k in ("maxspeed", "maxspeed:forward", "zone:maxspeed", "maxspeed:type", "source:maxspeed"):
        v = parse_limit(t.get(k))
        if v is not None:
            return v
    return None


def default_limit(t, urban, germany):
    hw = t.get("highway", "")
    if hw.endswith("_link"):
        hw = hw[:-5]
    ref = t.get("ref", "")
    oneway = t.get("oneway") == "yes"
    if hw == "living_street":
        return 7 if germany else 20
    if hw == "motorway":
        return None if germany else 140
    if not germany and (t.get("motorroad") == "yes" or ref.startswith("S")):
        return 120 if oneway else 100
    if urban:
        return 50
    return 100 if germany else 90


def rdp(pts, eps):
    """Upraszczanie linii (Douglas-Peucker), pts w metrach lokalnych."""
    if len(pts) < 3:
        return list(range(len(pts)))
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        a, b = stack.pop()
        ax, ay = pts[a]
        bx, by = pts[b]
        dx, dy = bx - ax, by - ay
        l2 = dx * dx + dy * dy
        best, bi = -1.0, -1
        for i in range(a + 1, b):
            px, py = pts[i]
            if l2 == 0:
                d = math.hypot(px - ax, py - ay)
            else:
                t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / l2))
                d = math.hypot(px - (ax + t * dx), py - (ay + t * dy))
            if d > best:
                best, bi = d, i
        if best > eps:
            keep[bi] = True
            stack.append((a, bi))
            stack.append((bi, b))
    return [i for i, k in enumerate(keep) if k]


class Handler(osmium.SimpleHandler):
    def __init__(self, germany, ways, signs, seen):
        super().__init__()
        self.germany = germany
        self.ways = ways
        self.signs = signs
        self.seen = seen
        self.places = {}  # (gx, gy) -> [(lat, lon, r)]

    def _near_place(self, lat, lon):
        gx, gy = int(lon / 0.05), int(lat / 0.05)
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for (pl, po, r) in self.places.get((gx + dx, gy + dy), ()):
                    if dist(lat, lon, pl, po) < r:
                        return True
        return False

    def node(self, n):
        t = n.tags
        if not t or not n.location.valid():
            return
        lat, lon = n.location.lat, n.location.lon
        place = t.get("place")
        if place in PLACE_R or place == "neighbourhood":
            r = PLACE_R.get(place, 500.0)
            self.places.setdefault((int(lon / 0.05), int(lat / 0.05)), []).append((lat, lon, r))
            return
        kind = None
        if t.get("railway") == "level_crossing":
            kind = "rail"
        else:
            hw = t.get("highway")
            if hw == "traffic_signals":
                kind = "signals"
            elif hw in ("stop", "give_way"):
                kind = hw
            elif hw == "speed_camera":
                kind = "camera"
            elif hw == "crossing" and t.get("crossing") not in ("unmarked", "no", "informal", "traffic_signals"):
                kind = "crossing"
        if kind and ("n", n.id) not in self.seen:
            self.seen.add(("n", n.id))
            self.signs.append((SIGN_KINDS[kind], lat, lon))

    def way(self, w):
        t = w.tags
        hw = t.get("highway")
        if not hw or not HW.match(hw) or ("w", w.id) in self.seen:
            return
        try:
            coords = [(nd.location.lat, nd.location.lon) for nd in w.nodes if nd.location.valid()]
        except osmium.InvalidLocationError:
            return
        if len(coords) < 2:
            return
        self.seen.add(("w", w.id))
        tags = {k: t.get(k) for k in ("highway", "ref", "name", "oneway", "motorroad", "zone:traffic",
                                      "maxspeed", "maxspeed:forward", "zone:maxspeed", "maxspeed:type", "source:maxspeed")
                if t.get(k) is not None}
        lim = exact_limit(tags)
        est = 0
        if lim is None:
            mid = coords[len(coords) // 2]
            urban = "urban" in tags.get("zone:traffic", "").lower() or hw == "residential" or self._near_place(*mid)
            lim = default_limit(tags, urban, self.germany)
            est = 1
        ref, name = tags.get("ref"), tags.get("name")
        label = f"{ref} · {name}" if ref and name else (ref or name or "")
        # uproszczenie geometrii (3 m)
        lat0 = coords[0][0]
        kx = 111_320.0 * math.cos(math.radians(lat0))
        pts = [((lo - coords[0][1]) * kx, (la - lat0) * 110_540.0) for la, lo in coords]
        coords = [coords[i] for i in rdp(pts, 3.0)]
        self.ways.append((w.id, min(lim or 0, 255), est, label, coords))


def varint(out, v):
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return


def zz(v):
    return (v << 1) ^ (v >> 63)


def utf(out, s):
    b = s.encode("utf-8")[:255]
    varint(out, len(b))
    out.extend(b)


def main():
    out_path, regions, inputs = sys.argv[1], sys.argv[2], sys.argv[3:]
    ways, signs, seen = [], [], set()
    for spec in inputs:
        path, country = spec.rsplit(":", 1)
        t0 = time.time()
        h = Handler(country.upper() == "DE", ways, signs, seen)
        h.apply_file(path, locations=True, idx="flex_mem")
        print(f"{path}: drogi {len(ways)}, znaki {len(signs)} ({time.time() - t0:.0f} s)", flush=True)

    tiles = {}
    for wid, lim, est, label, coords in ways:
        lats = [c[0] for c in coords]
        lons = [c[1] for c in coords]
        for tx in range(math.floor(min(lons) / TILE), math.floor(max(lons) / TILE) + 1):
            for ty in range(math.floor(min(lats) / TILE), math.floor(max(lats) / TILE) + 1):
                tiles.setdefault((tx, ty), ([], []))[0].append((wid, lim, est, label, coords))
    for kind, lat, lon in signs:
        tiles.setdefault((math.floor(lon / TILE), math.floor(lat / TILE)), ([], []))[1].append((kind, lat, lon))

    blobs = []
    for (tx, ty) in sorted(tiles):
        tw, ts = tiles[(tx, ty)]
        oy, ox = round(ty * TILE * SCALE), round(tx * TILE * SCALE)
        b = bytearray()
        varint(b, len(tw))
        for wid, lim, est, label, coords in tw:
            varint(b, wid)
            b.append(lim)
            b.append(est)
            utf(b, label)
            varint(b, len(coords))
            py, px = oy, ox
            for la, lo in coords:
                y, x = round(la * SCALE), round(lo * SCALE)
                varint(b, zz(y - py))
                varint(b, zz(x - px))
                py, px = y, x
        varint(b, len(ts))
        for kind, la, lo in ts:
            b.append(kind)
            varint(b, zz(round(la * SCALE) - oy))
            varint(b, zz(round(lo * SCALE) - ox))
        blobs.append((tx, ty, zlib.compress(bytes(b), 9)))

    head = bytearray(b"CSL1")
    head += struct.pack(">q", int(time.time() * 1000))
    rb = regions.encode("utf-8")
    varint(head, len(rb))
    head += rb
    head += struct.pack(">i", len(blobs))
    off = len(head) + 16 * len(blobs)
    index = bytearray()
    for tx, ty, z in blobs:
        index += struct.pack(">iiii", tx, ty, off, len(z))
        off += len(z)
    with open(out_path, "wb") as f:
        f.write(head)
        f.write(index)
        for _, _, z in blobs:
            f.write(z)
    known = sum(1 for w in ways if not w[2])
    print(f"Zapisano {out_path}: {len(blobs)} kafli, {len(ways)} dróg ({known} ze znakiem), {len(signs)} znaków, {off / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
