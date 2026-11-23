import { beforeAll, describe, expect, it } from 'vitest';
import { CR, KOPPEN, koppenGroup } from './climate';
import { CELL_KM, CELL_M, H, N, W, latOf, wrapDx } from './grid';
import { CAPITAL_NAME, GAMMA_PINS, GAMMA_PLAN_TILES, LAUNCH } from './hubs';
import { undrained } from './hydro';
import { isRealTownName } from './names';
import { HUB_SEP, PORT_SEP, hubRule, placeHubs, type Place } from './places';
import { sailingsPerWeek } from './sea';
import { isTrip, planTrip } from './travel';
import { generateWorld, type World } from './world';
import { PTS_SCALE, decodeSkeleton, encodeSkeleton } from './bake';
import { worldPaths, type Pt } from './paths';
import { linksOf, planRoute, stationOf, type Route } from '../rail';

// The world skeleton (WORLD_GEN.md phases W1-W3) on one seed, generated once (about 20 s), plus a second run for determinism.
const SEED = 'alpha';
let w: World;
beforeAll(() => { w = generateWorld(SEED); }, 240_000);

const fnv = (a: ArrayLike<number>): number => { let h = 2166136261; for (let i = 0; i < a.length; i++) { h ^= Math.round((a[i]! as number) * 1000) | 0; h = Math.imul(h, 16777619); } return h >>> 0; };
const factsOf = (x: World): string => JSON.stringify({
  e: fnv(x.terrain.elev), t: fnv(x.climate.tempM), r: fnv(x.climate.rainM), f: fnv(x.hydro.flow), k: fnv(x.climate.koppen),
  p: x.places.map((p) => [p.id, p.name, p.x, p.z, p.founded, p.type, p.rank]), roads: x.roads.map((r) => [r.id, r.cells.length, r.built, r.paved, r.expressway]),
  rail: x.rail.links.map((l) => [l.id, l.a, l.b, l.built, l.passengerEnd, l.abandoned]), sea: x.sea.routes.map((r) => [r.id, r.a, r.b, r.km, r.hours]), dams: x.dams.map((d) => [d.cell, d.year]),
  wx: x.weather.regions.slice(0, 400),
});

describe('determinism', () => {
  it('the same seed makes the same world', () => {
    const again = generateWorld(SEED);
    expect(factsOf(again)).toBe(factsOf(w));
  }, 240_000);
  it('wraps east-west without a seam', () => {
    // the height step across the seam is like any other column step
    let seam = 0, other = 0;
    for (let j = 0; j < H; j++) { seam += Math.abs(w.terrain.elev[j * W]! - w.terrain.elev[j * W + W - 1]!); other += Math.abs(w.terrain.elev[j * W + 500]! - w.terrain.elev[j * W + 499]!); }
    expect(seam).toBeLessThan(other * 2.5);
  });
});

describe('W1 terrain', () => {
  it('has 30% land outside the ice caps in 8-10 continents of very roughly 200-450 km, and big islands', () => {
    let land = 0, cells = 0;
    for (let j = 0; j < H; j++) if (Math.abs(latOf(j + 0.5)) < 72) for (let i = 0; i < W; i++) { cells++; land += w.terrain.land[j * W + i]!; }
    expect(land / cells).toBeGreaterThan(0.28);
    expect(land / cells).toBeLessThan(0.32);
    const conts = w.terrain.masses.filter((m) => m.kind === 'continent');
    expect(conts.length).toBeGreaterThanOrEqual(8);
    expect(conts.length).toBeLessThanOrEqual(10);
    for (const c of conts) { expect(c.longKm).toBeGreaterThan(150); expect(c.longKm).toBeLessThan(650); }
    expect(w.terrain.masses.some((m) => m.kind === 'big island')).toBe(true);
  });
  it('every river reaches the sea or a lake', () => {
    expect(w.hydro.rivers.length).toBeGreaterThan(200);
    expect(undrained(w.terrain, w.hydro)).toHaveLength(0);
    for (const r of w.hydro.rivers) expect(['sea', 'lake', 'river']).toContain(r.end);
  });
  it('lakes have a level and an outlet', () => {
    for (const l of w.hydro.lakes) { expect(l.outlet).toBeGreaterThanOrEqual(0); expect(w.hydro.lake[l.outlet]).toBe(l.id); }
  });
});

describe('W1 climate', () => {
  const cl = () => w.climate;
  const landCells = (f: (c: number, lat: number) => boolean): number[] => { const out: number[] = []; for (let c = 0; c < N; c += 3) if (w.terrain.land[c] && !w.hydro.lake[c] && f(c, latOf(((c / W) | 0) + 0.5))) out.push(c); return out; };
  const mean = (a: number[], v: (c: number) => number): number => a.reduce((s, c) => s + v(c), 0) / Math.max(1, a.length);

  it('temperatures fall with latitude and with height', () => {
    const band = (lo: number, hi: number): number => mean(landCells((c, l) => Math.abs(l) >= lo && Math.abs(l) < hi && w.terrain.elev[c]! < 300), (c) => cl().temp[c]!);
    expect(band(0, 12)).toBeGreaterThan(band(20, 30));
    expect(band(20, 30)).toBeGreaterThan(band(40, 50));
    expect(band(40, 50)).toBeGreaterThan(band(55, 65));
    const low = mean(landCells((c, l) => Math.abs(l) > 25 && Math.abs(l) < 50 && w.terrain.elev[c]! < 300), (c) => cl().temp[c]!);
    const high = mean(landCells((c, l) => Math.abs(l) > 25 && Math.abs(l) < 50 && w.terrain.elev[c]! > 1500), (c) => cl().temp[c]!);
    expect(low - high).toBeGreaterThan(6);
  });
  it('the seasons flip between the hemispheres', () => {
    const north = landCells((_, l) => l < -30 && l > -55), south = landCells((_, l) => l > 30 && l < 55);
    const jan = (c: number): number => cl().tempM[c]!, jul = (c: number): number => cl().tempM[6 * N + c]!;
    expect(mean(north, jul)).toBeGreaterThan(mean(north, jan) + 5);
    expect(mean(south, jan)).toBeGreaterThan(mean(south, jul) + 5);
  });
  it('deserts sit under the subtropical highs and in rain shadows, not at random', () => {
    const desert = landCells((c) => KOPPEN[cl().koppen[c]!]!.startsWith('BW'));
    expect(desert.length).toBeGreaterThan(50);
    const explained = desert.filter((c) => {
      const lat = Math.abs(latOf(((c / W) | 0) + 0.5));
      if (lat > 14 && lat < 42) return true; // under the highs (they move with the seasons)
      if (cl().current[c]! < -1) return true; // a cold-current coast
      // a rain shadow: high ground upwind (either wind of the year) within 250 km
      const j = (c / W) | 0, i = c % W;
      for (const dir of [-1, 1]) for (let k = 1; k * CELL_KM < 250; k++) if (w.terrain.elev[j * W + ((i + dir * k + W * 4) % W)]! > w.terrain.elev[c]! + 600) return true;
      return w.terrain.distSea[c]! > 120; // deep in the interior
    });
    expect(explained.length / desert.length).toBeGreaterThan(0.9);
    // and the subtropics are drier than the equator and the mid-latitudes
    const dry = (lo: number, hi: number): number => mean(landCells((_, l) => Math.abs(l) >= lo && Math.abs(l) < hi), (c) => (koppenGroup(cl().koppen[c]!) === 'B' ? 1 : 0));
    expect(dry(18, 35)).toBeGreaterThan(dry(0, 10));
    expect(dry(18, 35)).toBeGreaterThan(dry(45, 60));
  });
  it('windward coasts are wetter than leeward ones', () => {
    // a coast cell faces west when the sea is just to its west and land to its east
    const facing = (c: number, dir: 1 | -1): boolean => {
      const j = (c / W) | 0, i = c % W;
      const sea = (k: number): boolean => !w.terrain.land[j * W + ((i + k + W) % W)];
      return sea(-dir * 3) && sea(-dir * 6) && !sea(dir * 3) && !sea(dir * 8);
    };
    const coast = (lo: number, hi: number, dir: 1 | -1): number => mean(landCells((c, l) => Math.abs(l) >= lo && Math.abs(l) < hi && w.terrain.distSea[c]! < 12 && facing(c, dir)), (c) => cl().rain[c]!);
    // westerlies (40-55 deg): west-facing coasts wetter; trades (8-22 deg): east-facing coasts wetter
    expect(coast(40, 55, 1)).toBeGreaterThan(coast(40, 55, -1));
    expect(coast(8, 22, -1)).toBeGreaterThan(coast(8, 22, 1));
  });
  it('gives weather statistics per region: hurricane coasts, tornado belts, snow, fog, heat, drought', () => {
    const rs = w.weather.regions;
    expect(rs.length).toBe((W / 16) * (H / 16));
    expect(rs.some((r) => r.hurricane > 0.1)).toBe(true);
    expect(rs.some((r) => r.snowDays > 30)).toBe(true);
    expect(rs.some((r) => r.fogDays > 20)).toBe(true);
    expect(rs.some((r) => r.heatDays > 10)).toBe(true);
    expect(rs.some((r) => r.drought > 0.3)).toBe(true);
    expect(rs.some((r) => r.tornado > 0.05)).toBe(true);
    // the climate tags a town can talk about
    expect([...w.tags.values()].some((t) => t.length > 0)).toBe(true);
  });
});

describe('W2 places', () => {
  it('puts the capital first, on the largest continent, with gamma\'s plan and its placeholder name', () => {
    const cap = w.hubs[0]!;
    expect(cap.type).toBe('capital');
    expect(cap.name).toBe(CAPITAL_NAME);
    expect(cap.seed).toBe('gamma');
    expect(cap.mass).toBe(w.startMass);
    expect(w.terrain.masses[cap.mass - 1]!.areaKm2).toBe(Math.max(...w.terrain.masses.map((m) => m.areaKm2)));
    // the capital's continent holds it and about three launch hubs
    const launchHere = w.hubs.filter((h) => h.hub!.launch && h.mass === cap.mass && h !== cap).length;
    expect(launchHere).toBeGreaterThanOrEqual(2);
    expect(launchHere).toBeLessThanOrEqual(4);
  });
  it('every hub obeys its site rule and its climate fits its type, and hubs keep apart', () => {
    for (const h of w.hubs) {
      const ok = hubRule(h.hub!.type, w.terrain, w.climate, w.hydro, w.fields, h.cell);
      expect(ok.ok, `${h.name} (${h.type}): ${ok.why}`).toBe(true);
      const k = KOPPEN[w.climate.koppen[h.cell]!]!;
      if (h.type === 'farm town') { expect([CR.wheat, CR.corn, CR.cotton, CR.orchards, CR.rice, CR.dairy]).toContain(w.crop[h.cell]); expect(k.startsWith('BW')).toBe(false); }
      if (h.type === 'mill town') expect(w.hydro.steady[h.cell]).toBeGreaterThanOrEqual(0.25);
      if (h.type === 'capital') expect(['C', 'D']).toContain(koppenGroup(w.climate.koppen[h.cell]!));
      if (h.type === 'port') expect(w.hydro.coast[h.cell]).toBeGreaterThan(0);
    }
    for (const a of w.hubs) for (const b of w.hubs) {
      if (a === b) continue;
      const d = Math.hypot(wrapDx(a.x - b.x), a.z - b.z) * CELL_KM;
      expect(d).toBeGreaterThan(a.type === 'port' && b.type === 'port' ? PORT_SEP - 2 : HUB_SEP - 2);
    }
  });
  it('places the launch hubs first and never moves them when later hubs are added', () => {
    const few = placeHubs(w.terrain, w.climate, w.hydro, w.fields, 1 + LAUNCH.length);
    expect(few.hubs.map((h) => [h.id, h.cell])).toEqual(w.hubs.slice(0, few.hubs.length).map((h) => [h.id, h.cell]));
    expect(w.hubs.length).toBeGreaterThanOrEqual(20);
  });
  it('pins the capital\'s highways, rail and river to gamma\'s plan', () => {
    const cap = w.hubs[0]!;
    const near = (c: number, kind: string): boolean => cap.pins!.filter((p) => p.kind === kind).some((p) => Math.hypot(wrapDx((c % W) + 0.5 - p.x), ((c / W) | 0) + 0.5 - p.z) < 2.6);
    const hw = w.roads.filter((r) => r.cls === 'highway' && (r.a === cap.id || r.b === cap.id));
    expect(hw.length).toBeGreaterThan(0);
    for (const r of hw) expect(near(r.a === cap.id ? r.cells[1]! : r.cells[r.cells.length - 2]!, 'highway'), r.id).toBe(true);
    const rl = w.rail.lines.filter((l) => l.stations.includes(cap.id) && (l.cells[0] === cap.cell || l.cells[l.cells.length - 1] === cap.cell));
    expect(rl.length).toBeGreaterThan(0);
    for (const l of rl) expect(near(l.cells[0] === cap.cell ? l.cells[1]! : l.cells[l.cells.length - 2]!, 'rail'), l.id).toBe(true);
    // the world river runs through the plan's river column, north-south
    expect(w.hydro.flow[cap.cell]).toBeGreaterThan(0);
    expect(cap.pins!.filter((p) => p.kind === 'river').map((p) => p.name).sort()).toHaveLength(2);
  });
  it('gamma\'s pins are where planTown puts its river, Main Street and the main line', async () => {
    const { planTown } = await import('../../town/plan');
    const p = planTown('gamma');
    expect([p.width, p.height, p.center.x, p.center.y]).toEqual([GAMMA_PLAN_TILES.w, GAMMA_PLAN_TILES.h, GAMMA_PLAN_TILES.cx, GAMMA_PLAN_TILES.cy]);
    const mains = p.roads.filter((r) => r.main && r.horizontal).sort((a, b) => a.x - b.x);
    const west = GAMMA_PINS.find((q) => q.kind === 'highway' && q.name === 'west')!;
    expect(Math.abs(west.dx - (mains[0]!.x - p.center.x) * 2.1)).toBeLessThan(5);
    // the river column crosses the top row near the pin
    const m = p.water!.mask, top: number[] = [];
    for (let x = 0; x < p.water!.width; x++) if (m[x]) top.push(x);
    const north = GAMMA_PINS.find((q) => q.name === 'river north')!;
    expect(Math.abs(north.dx - ((top[0]! + top[top.length - 1]!) / 2 - p.center.x) * 2.1)).toBeLessThan(15);
  }, 120_000);
  it('places towns 12-25 km apart and hamlets between them; names are unique and made up', () => {
    expect(w.towns.length).toBeGreaterThan(500);
    expect(w.hamlets.length).toBeGreaterThan(w.towns.length);
    const names = w.places.map((p) => p.name.toLowerCase());
    expect(new Set(names).size).toBe(names.length);
    for (const p of w.places) expect(isRealTownName(p.name), p.name).toBe(false);
    for (const p of w.towns.slice(0, 200)) for (const q of w.towns.slice(0, 200)) if (p !== q) expect(Math.hypot(wrapDx(p.x - q.x), p.z - q.z) * CELL_KM).toBeGreaterThan(11.5);
  });
});

describe('W3 links', () => {
  const connected = (ids: string[], adj: Map<string, string[]>): boolean => {
    if (!ids.length) return true;
    const seen = new Set([ids[0]!]), st = [ids[0]!];
    while (st.length) for (const n of adj.get(st.pop()!) ?? []) if (!seen.has(n)) { seen.add(n); st.push(n); }
    return ids.every((i) => seen.has(i));
  };
  it('every town and hamlet is reachable by road from the rest of its landmass', () => {
    const byMass = new Map<number, Place[]>();
    for (const p of w.places) byMass.set(p.mass, [...(byMass.get(p.mass) ?? []), p]);
    for (const [, ps] of byMass) {
      if (ps.length < 2) continue;
      const a = ps[0]!;
      for (const b of ps.slice(1).filter((_, i) => i % 7 === 0)) expect(w.roadGraph.drive(a.id, b.id, 2025), `${a.name} -> ${b.name}`).not.toBeNull();
    }
  });
  it('every hub on a continent is on the rail network with the others there', () => {
    const adj = new Map<string, string[]>();
    for (const l of w.rail.links) if (l.kind === 'main') { adj.set(l.a, [...(adj.get(l.a) ?? []), l.b]); adj.set(l.b, [...(adj.get(l.b) ?? []), l.a]); }
    const byMass = new Map<number, string[]>();
    for (const h of w.hubs) byMass.set(h.mass, [...(byMass.get(h.mass) ?? []), h.id]);
    for (const [, ids] of byMass) if (ids.length > 1) expect(connected(ids, adj), ids.join()).toBe(true);
    // and the trains run between them (rail.ts's planner on the skeleton)
    const cap = w.hubs[0]!;
    for (const h of w.hubs.filter((q) => q.mass === cap.mass && q !== cap)) {
      const r = planRoute(w.railWorld, cap.id, h.id, 1955);
      expect((r as Route).legs?.some((l) => l.mode === 'train'), `${cap.name} -> ${h.name}`).toBe(true);
    }
    expect(stationOf(w.railWorld, cap.id, 1955).status).toBe('passenger');
    expect(linksOf(w.railWorld, cap.id).every((l) => l.kind === 'main')).toBe(true);
  });
  it('every port pair has a sea route, in every year', () => {
    const ports = [...w.sea.ports, ...w.sea.landings].map((p) => p.id);
    const adj = new Map<string, string[]>();
    for (const r of w.sea.routes) { adj.set(r.a, [...(adj.get(r.a) ?? []), r.b]); adj.set(r.b, [...(adj.get(r.b) ?? []), r.a]); }
    expect(connected(ports, adj)).toBe(true);
    for (const y of [1920, 1955, 1975, 2000, 2025]) for (const r of w.sea.routes) expect(sailingsPerWeek(r, y).perWeek, `${r.id} ${y}`).toBeGreaterThan(0);
    const hp = w.sea.ports;
    for (let k = 0; k < hp.length; k++) {
      const a = hp[k]!, b = hp[(k + 3) % hp.length]!;
      if (a.mass === b.mass) continue;
      const t = planTrip(w, a.id, b.id, 1955);
      expect(isTrip(t) && t.legs.some((l) => l.mode === 'sea'), `${a.name} -> ${b.name}`).toBe(true);
    }
  });
  it('every town an NPC can name is routable (planTrip returns a trip) in 1930, 1955, 1975, 2000 and 2025', () => {
    const cap = w.hubs[0]!;
    const origins = w.towns.filter((_, i) => i % Math.floor(w.towns.length / 10) === 0).slice(0, 10);
    for (const o of origins) {
      // their county's towns (within 40 km), every hub on their landmass, the capital, the hub ports, their legend's target
      const known = new Set<string>([cap.id]);
      for (const p of w.towns) if (p.mass === o.mass && p !== o && Math.hypot(wrapDx(p.x - o.x), p.z - o.z) * CELL_KM < 40) known.add(p.id);
      for (const h of w.hubs) if (h.mass === o.mass || h.type === 'port') known.add(h.id);
      const nearest = w.towns.filter((p) => p.mass === o.mass && p !== o).sort((a, b) => Math.hypot(wrapDx(a.x - o.x), a.z - o.z) - Math.hypot(wrapDx(b.x - o.x), b.z - o.z))[0];
      if (nearest) known.add(nearest.id);
      for (const y of [1930, 1955, 1975, 2000, 2025]) for (const id of known) {
        const t = planTrip(w, o.id, id, y);
        expect(isTrip(t), `${o.name} -> ${w.byId.get(id)!.name} in ${y}: ${!isTrip(t) ? t.reason : ''}`).toBe(true);
      }
    }
  }, 120_000);
  it('keeps history by year: roads paved and rebuilt, rail opening and closing, dams, growth', () => {
    expect(w.roads.filter((r) => r.cls === 'highway').every((r) => r.paved <= 1938 && r.built < r.paved)).toBe(true);
    expect(w.roads.some((r) => r.expressway !== undefined && r.expressway >= 1956)).toBe(true);
    expect(w.rail.links.filter((l) => l.kind === 'main').every((l) => l.abandoned === undefined)).toBe(true);
    expect(w.rail.links.some((l) => l.kind === 'branch' && l.abandoned !== undefined)).toBe(true);
    for (const d of w.dams) { expect(d.year).toBeGreaterThanOrEqual(1930); expect(d.year).toBeLessThanOrEqual(1970); }
  });
});

describe('the baked skeleton', () => {
  it('round-trips its facts', () => {
    const bin = encodeSkeleton(w);
    const back = decodeSkeleton(bin);
    expect(back.seed).toBe(SEED);
    expect(back.places.length).toBe(w.places.length);
    expect(back.places[0]!.name).toBe(CAPITAL_NAME);
    for (let c = 0; c < N; c += 997) expect(Math.abs(back.elev[c]! - Math.round(w.terrain.elev[c]!))).toBeLessThanOrEqual(1);
    expect(back.roads.length).toBe(w.roads.length);
    expect(back.railLinks.length).toBe(w.rail.links.length);
  });
});

describe('W4 paths: the vectors meet the hubs at their pins', () => {
  it('the capital\'s highways and rail start exactly on its pins, its river is bent through its river pins', () => {
    const P = worldPaths(w);
    const cap = w.hubs[0]!;
    const on = (p: Pt, kind: string): boolean => cap.pins!.some((q) => q.kind === kind && Math.hypot(wrapDx(p[0] - q.x), p[1] - q.z) * CELL_M < 0.01);
    let roads = 0, lines = 0;
    w.roads.forEach((r, k) => {
      if (r.a === cap.id) { expect(on(P.roads[k]![0]!, 'highway'), r.id).toBe(true); roads++; }
      if (r.b === cap.id) { expect(on(P.roads[k]!.at(-1)!, 'highway'), r.id).toBe(true); roads++; }
    });
    w.rail.lines.forEach((l, k) => {
      if (l.cells[0] === cap.cell) { expect(on(P.rail[k]![0]!, 'rail'), l.id).toBe(true); lines++; }
      if (l.cells.at(-1) === cap.cell) { expect(on(P.rail[k]!.at(-1)!, 'rail'), l.id).toBe(true); lines++; }
    });
    expect(roads).toBeGreaterThan(0);
    expect(lines).toBeGreaterThan(0);
    const pin = cap.pins!.find((q) => q.name.startsWith('river in'))!, pout = cap.pins!.find((q) => q.name.startsWith('river out'))!;
    const k = w.hydro.rivers.findIndex((r) => r.cells.includes(cap.cell));
    const path = P.rivers[k]!;
    const i = path.findIndex((p) => Math.hypot(wrapDx(p[0] - pin.x), p[1] - pin.z) * CELL_M < 0.01);
    expect(i).toBeGreaterThan(0);
    expect(Math.hypot(wrapDx(path[i + 1]![0] - pout.x), path[i + 1]![1] - pout.z) * CELL_M).toBeLessThan(0.01);
    // nothing of any river inside the plan except the pins themselves
    const pw = (GAMMA_PLAN_TILES.w * 2.1) / 2, ph = (GAMMA_PLAN_TILES.h * 2.1) / 2;
    for (const line of P.rivers) for (const p of line) {
      const dx = wrapDx(p[0] - cap.x) * CELL_M, dz = (p[1] - cap.z) * CELL_M;
      const pinned = Math.hypot(wrapDx(p[0] - pin.x), p[1] - pin.z) * CELL_M < 0.01 || Math.hypot(wrapDx(p[0] - pout.x), p[1] - pout.z) * CELL_M < 0.01;
      if (!pinned) expect(Math.abs(dx - (pin.x - cap.x) * CELL_M) < pw && Math.abs(dz) < ph - 1).toBe(false);
    }
    // the baked paths are the same (fixed point: 2 mm)
    const back = decodeSkeleton(encodeSkeleton(w));
    const pts = back.section('pts') as Int32Array;
    const rv = (back.json.rivers as { path: [number, number] }[])[k]!.path;
    expect(rv[1]).toBe(path.length);
    expect(Math.abs(pts[(rv[0] + i) * 2]! / PTS_SCALE - pin.x) * CELL_M).toBeLessThan(0.003);
  });
});
