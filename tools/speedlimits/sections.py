#!/usr/bin/env python3
"""
Odcinkowe pomiary prędkości (OPP) z OpenStreetMap dla CAR OS.

Użycie: sections.py out.json --enf a.enf.pbf [b.enf.pbf ...] --roads drogi.pbf [...]

  *.enf.pbf  – relacje type=enforcement (osmium tags-filter r/enforcement, z członkami),
  drogi.pbf  – główne drogi (osmium tags-filter w/highway=motorway,trunk,...).

Bierze relacje enforcement=average_speed (członkowie from / to / device), wyznacza trasę
odcinka po drogach (Dijkstra) i zapisuje JSON:
  [{"id": 123, "name": "...", "max": 100, "len": 5230, "approx": false, "path": [lat, lon, lat, lon, ...]}, ...]
"""
import heapq
import json
import math
import re
import sys

import osmium

GRID = 0.02
MAJOR = re.compile(r"^(motorway|trunk|primary|secondary|tertiary|unclassified)(_link)?$")


def dist(a_lat, a_lon, b_lat, b_lon):
    kx = 111_320.0 * math.cos(math.radians(a_lat))
    return math.hypot((a_lon - b_lon) * kx, (a_lat - b_lat) * 110_540.0)


def parse_speed(v):
    if not v:
        return None
    m = re.match(r"^\s*(\d{2,3})\s*(mph)?", v)
    if not m:
        return None
    n = int(m.group(1))
    return round(n * 1.609) if m.group(2) else n


def rdp(pts, eps):
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
            t = 0.0 if l2 == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / l2))
            d = math.hypot(px - (ax + t * dx), py - (ay + t * dy))
            if d > best:
                best, bi = d, i
        if best > eps:
            keep[bi] = True
            stack.append((a, bi))
            stack.append((bi, b))
    return [i for i, k in enumerate(keep) if k]


class Enf(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.nodes = {}
        self.rels = []

    def node(self, n):
        if n.location.valid():
            self.nodes[n.id] = (n.location.lat, n.location.lon)

    def relation(self, r):
        t = r.tags
        if t.get("type") != "enforcement":
            return
        kind = t.get("enforcement", "")
        if kind not in ("average_speed", "section_control", "section"):
            return
        roles = {"from": [], "to": [], "device": []}
        for m in r.members:
            if m.type == "n" and m.role in roles:
                roles[m.role].append(m.ref)
        name = t.get("name") or t.get("description") or ""
        self.rels.append({
            "id": r.id, "name": name, "max": parse_speed(t.get("maxspeed")),
            "from": roles["from"], "to": roles["to"], "device": roles["device"],
        })


class Roads(osmium.SimpleHandler):
    def __init__(self, cells):
        super().__init__()
        self.cells = cells
        self.coord = {}
        self.adj = {}
        self.ways = 0

    def way(self, w):
        hw = w.tags.get("highway", "")
        if not MAJOR.match(hw):
            return
        pts = []
        hit = False
        for n in w.nodes:
            if not n.location.valid():
                continue
            la, lo = n.location.lat, n.location.lon
            pts.append((n.ref, la, lo))
            if not hit and (int(la // GRID), int(lo // GRID)) in self.cells:
                hit = True
        if not hit or len(pts) < 2:
            return
        self.ways += 1
        ow = w.tags.get("oneway", "")
        oneway = ow in ("yes", "1", "true") or hw == "motorway" or w.tags.get("junction") in ("roundabout", "circular")
        if ow == "-1":
            pts.reverse()
            oneway = True
        ms = parse_speed(w.tags.get("maxspeed"))
        for ref, la, lo in pts:
            self.coord[ref] = (la, lo)
        for (a, la1, lo1), (b, la2, lo2) in zip(pts, pts[1:]):
            d = dist(la1, lo1, la2, lo2)
            self.adj.setdefault(a, []).append((b, d, ms))
            if not oneway:
                self.adj.setdefault(b, []).append((a, d, ms))


def snap(roads, node, pos):
    if node is not None and node in roads.adj:
        return node
    la, lo = pos
    best, bn = 80.0, None
    for ref, (a, b) in roads.coord.items():
        if abs(a - la) > 0.002 or abs(b - lo) > 0.003:
            continue
        d = dist(la, lo, a, b)
        if d < best and ref in roads.adj:
            best, bn = d, ref
    return bn


def route(roads, a, b, limit):
    dist_to = {a: 0.0}
    prev = {}
    pq = [(0.0, a)]
    while pq:
        d, u = heapq.heappop(pq)
        if u == b:
            break
        if d > dist_to.get(u, 1e18) or d > limit:
            continue
        for v, w, ms in roads.adj.get(u, ()):
            nd = d + w
            if nd < dist_to.get(v, 1e18):
                dist_to[v] = nd
                prev[v] = (u, ms)
                heapq.heappush(pq, (nd, v))
    if b not in dist_to:
        return None
    path, speeds = [b], []
    while path[-1] != a:
        u, ms = prev[path[-1]]
        if ms:
            speeds.append(ms)
        path.append(u)
    path.reverse()
    return path, dist_to[b], speeds


def main():
    out = sys.argv[1]
    enf, roads_in, cur = [], [], None
    for a in sys.argv[2:]:
        if a == "--enf":
            cur = enf
        elif a == "--roads":
            cur = roads_in
        elif cur is not None:
            cur.append(a)
    h = Enf()
    for f in enf:
        h.apply_file(f)
    pairs = []
    for r in h.rels:
        if r["from"] and r["to"]:
            pairs.append((r, r["from"][0], r["to"][-1], 1))
        elif len(r["device"]) == 2:
            # kierunek nieznany – oba kierunki (drugi z ujemnym id, żeby id było unikalne)
            pairs.append((r, r["device"][0], r["device"][1], 1))
            pairs.append((r, r["device"][1], r["device"][0], -1))
    pairs = [p for p in pairs if p[1] in h.nodes and p[2] in h.nodes]
    print(f"relacji OPP: {len(h.rels)}, odcinków z punktami: {len(pairs)}")

    cells = set()
    for _, a, b, _ in pairs:
        (la1, lo1), (la2, lo2) = h.nodes[a], h.nodes[b]
        for i in range(int(min(la1, la2) // GRID) - 1, int(max(la1, la2) // GRID) + 2):
            for j in range(int(min(lo1, lo2) // GRID) - 1, int(max(lo1, lo2) // GRID) + 2):
                cells.add((i, j))
    roads = Roads(cells)
    for f in roads_in:
        roads.apply_file(f, locations=True)
    print(f"dróg w pobliżu odcinków: {roads.ways}, węzłów: {len(roads.coord)}")

    res, seen = [], set()
    for r, a, b, sign in pairs:
        pa, pb = h.nodes[a], h.nodes[b]
        straight = dist(*pa, *pb)
        if straight < 300 or straight > 60_000:
            continue
        key = (r["id"], a, b)
        if key in seen:
            continue
        seen.add(key)
        sa, sb = snap(roads, a, pa), snap(roads, b, pb)
        got = route(roads, sa, sb, straight * 1.6 + 1500) if sa and sb else None
        mx = r["max"]
        if got and got[1] <= straight * 1.6 + 1500:
            nodes, length, speeds = got
            pts = [roads.coord[n] for n in nodes]
            if not mx and speeds:
                mx = min(speeds)
            approx = False
        else:
            pts, length, approx = [pa, pb], straight * 1.03, True
        lat0 = pts[0][0]
        kx = 111_320.0 * math.cos(math.radians(lat0))
        keep = rdp([(p[1] * kx, p[0] * 110_540.0) for p in pts], 4.0)
        flat = []
        for i in keep:
            flat += [round(pts[i][0], 6), round(pts[i][1], 6)]
        res.append({"id": r["id"] * sign, "name": r["name"], "max": mx or 0, "len": round(length), "approx": approx, "path": flat})
        print(f"  {r['id']} {r['name'][:40]!r}: {round(length)} m, max {mx}, {'w linii prostej' if approx else str(len(keep)) + ' pkt'}")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(res, f, ensure_ascii=False, separators=(",", ":"))
    print(f"zapisano {len(res)} odcinków -> {out}")


if __name__ == "__main__":
    main()
