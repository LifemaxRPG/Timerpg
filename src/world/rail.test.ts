import { describe, expect, it } from 'vitest';
import { loreOf } from './lore';
import { siteAt } from './map';
import {
  PASSENGER_SERVICE, alignment, departures, ewLink, fare, findTown, linkLength, linksAt, nsLink, otherEnd, planRoute,
  railFact, railSite, searchTowns, stationAt, stationEvents, trainEra, trainSpeed, tilesToKm, type RailWorld, type Route,
} from './rail';

const W: RailWorld = { seed: 'w1' };
const cells: [number, number][] = [];
for (let cy = -3; cy <= 3; cy++) for (let cx = -4; cx <= 4; cx++) cells.push([cx, cy]);
const isRoute = (r: unknown): r is Route => !!r && (r as Route).legs !== undefined;

describe('rail network', () => {
  it('is deterministic per world seed, and different between worlds', () => {
    expect(JSON.stringify(linksAt(W, 2, -1))).toBe(JSON.stringify(linksAt(W, 2, -1)));
    const a = cells.map(([x, y]) => linksAt(W, x, y).map((l) => l.id + l.built).join()).join();
    const b = cells.map(([x, y]) => linksAt({ seed: 'w2' }, x, y).map((l) => l.id + l.built).join()).join();
    expect(a).not.toBe(b);
  });

  it('has an endless grid of main lines: every even row east-west, a north-south column in every block of four', () => {
    for (let cy = -4; cy <= 4; cy += 2) for (let cx = -6; cx <= 6; cx++) expect(ewLink(W, cx, cy)?.kind).toBe('main');
    for (let b = -3; b <= 3; b++) {
      const cols = [0, 1, 2, 3].map((i) => b * 4 + i).filter((cx) => nsLink(W, cx, 0)?.kind === 'main');
      expect(cols).toHaveLength(1);
      for (let cy = -3; cy <= 3; cy++) expect(nsLink(W, cols[0]!, cy)?.kind).toBe('main');
    }
  });

  it('links agree from both ends, and the lines follow the roads east-west', () => {
    for (const [cx, cy] of cells) for (const l of linksAt(W, cx, cy)) {
      const [ox, oy] = otherEnd(l, `${cx},${cy}`).split(',').map(Number) as [number, number];
      expect(linksAt(W, ox, oy).map((x) => x.id)).toContain(l.id);
      expect(Math.abs(ox - cx) + Math.abs(oy - cy)).toBe(1);
    }
  });

  it('puts every town called Junction on two lines', () => {
    let n = 0;
    for (let cy = -12; cy <= 12; cy++) for (let cx = -12; cx <= 12; cx++) {
      const s = siteAt(W.seed, cx, cy);
      if (!/\bJunction\b/.test(s.name)) continue;
      n++;
      const dirs = new Set(linksAt(W, cx, cy).map((l) => l.dir));
      expect(dirs.size, s.name).toBe(2);
    }
    expect(n).toBeGreaterThan(3);
  });

  it('was built before 1950 (most of it before 1915)', () => {
    const built = cells.flatMap(([x, y]) => linksAt(W, x, y)).map((l) => l.built);
    expect(Math.max(...built)).toBeLessThan(1950);
    expect(built.filter((y) => y <= 1915).length / built.length).toBeGreaterThan(0.7);
  });

  it('loses branch passenger trains in the 1950s-60s, and lifts some branches (rail trails on some)', () => {
    const branches = [...new Map(cells.flatMap(([x, y]) => linksAt(W, x, y)).filter((l) => l.kind === 'branch').map((l) => [l.id, l])).values()];
    expect(branches.length).toBeGreaterThan(10);
    for (const l of branches) { expect(l.passengerEnd).toBeDefined(); expect(l.passengerEnd!).toBeLessThan(PASSENGER_SERVICE.from); }
    expect(branches.filter((l) => l.passengerEnd! >= 1950).length / branches.length).toBeGreaterThan(0.7);
    expect(branches.some((l) => l.abandoned !== undefined)).toBe(true);
    expect(branches.some((l) => l.trail !== undefined)).toBe(true);
    expect(branches.some((l) => l.abandoned === undefined)).toBe(true);
  });
});

describe('stations by year', () => {
  const open = (y: number): number => cells.filter(([x, c]) => stationAt(W, x, c, y).status === 'passenger').length;
  it('decline through the 1950s-60s and are fewest under the national service', () => {
    expect(open(1945)).toBeGreaterThan(open(1965));
    expect(open(1965)).toBeGreaterThan(open(1975));
    expect(open(1975)).toBeGreaterThan(0);
  });
  it('from 1971 are on main lines only, run by the national service', () => {
    for (const [x, y] of cells) {
      const st = stationAt(W, x, y, 1990);
      if (st.status !== 'passenger') continue;
      expect(st.service).toBe(PASSENGER_SERVICE.full);
      expect(linksAt(W, x, y).some((l) => l.kind === 'main')).toBe(true);
    }
  });
  it('are none before the railroad, freight or gone after the passengers', () => {
    for (const [x, y] of cells) {
      const all = linksAt(W, x, y);
      if (!all.length) { expect(stationAt(W, x, y, 2000).status).toBe('none'); continue; }
      const first = Math.min(...all.map((l) => l.built));
      expect(stationAt(W, x, y, first - 1).status).toBe('none');
      expect(stationAt(W, x, y, first).status).not.toBe('none');
    }
  });
  it('tell their history and a line for the NPCs', () => {
    const ev = stationEvents(W, 0, 0);
    expect(ev.length).toBeGreaterThan(1);
    for (let i = 1; i < ev.length; i++) expect(ev[i]!.year).toBeGreaterThanOrEqual(ev[i - 1]!.year);
    for (const y of [1900, 1955, 1980, 2025]) expect(railFact(W, 0, 0, y).length).toBeGreaterThan(20);
  });
  it('the home town replaces cell 0,0', () => {
    const h = railSite({ seed: 'w1', home: 'gamma' }, 0, 0);
    expect(h.seed).toBe('gamma');
    expect(railSite({ seed: 'w1', home: 'gamma' }, 1, 0)).toEqual(siteAt('w1', 1, 0));
  });
});

describe('track and timetables', () => {
  it('alignments join the two towns and are a little longer than the straight line', () => {
    for (const [x, y] of cells) for (const l of linksAt(W, x, y)) {
      const p = alignment(W, l);
      const a = railSite(W, ...(l.a.split(',').map(Number) as [number, number])), b = railSite(W, ...(l.b.split(',').map(Number) as [number, number]));
      const straight = Math.hypot(p[p.length - 1]!.x - p[0]!.x, p[p.length - 1]!.y - p[0]!.y);
      expect(linkLength(W, l)).toBeGreaterThanOrEqual(straight - 1e-6);
      expect(linkLength(W, l)).toBeLessThan(straight * 1.25);
      expect(Math.abs(p[0]!.x - a.center.x) + Math.abs(p[0]!.y - a.center.y)).toBeLessThan(150);
      expect(Math.abs(p[p.length - 1]!.x - b.center.x) + Math.abs(p[p.length - 1]!.y - b.center.y)).toBeLessThan(150);
    }
  });
  it('a train leaving one station is a departure at the next, a link\'s running time later', () => {
    for (const year of [1940, 1960, 1990, 2020]) for (let cx = -3; cx < 3; cx++) {
      const l = ewLink(W, cx, 0)!;
      const run = (tilesToKm(linkLength(W, l)) / trainSpeed('main', year)) * 60;
      const here = departures(W, 'ew:0', cx, 1, year), next = departures(W, 'ew:0', cx + 1, 1, year);
      const head = here[1]! - here[0]!;
      const mod = (v: number): number => ((v % head) + head) % head;
      expect(Math.min(mod(here[0]! + run - next[0]!), head - mod(here[0]! + run - next[0]!))).toBeLessThan(0.15);
      const back = departures(W, 'ew:0', cx + 1, -1, year), backNext = departures(W, 'ew:0', cx, -1, year);
      expect(Math.min(mod(back[0]! + run - backNext[0]!), head - mod(back[0]! + run - backNext[0]!))).toBeLessThan(0.15);
    }
  });
  it('fares rise with the years and the distance; steam gives way to diesel in the 1950s', () => {
    expect(fare(1950, 10)).toBeLessThan(fare(1980, 10));
    expect(fare(1980, 10)).toBeLessThan(fare(2025, 10));
    expect(fare(2025, 50)).toBeGreaterThan(fare(2025, 5));
    expect(trainEra(1945)).toBe('steam');
    expect(trainEra(1960)).toBe('diesel');
    expect(trainEra(1985)).toBe('national');
    expect(trainEra(2020)).toBe('modern');
  });
});

describe('route planner', () => {
  const years = [1930, 1955, 1966, 1975, 2000, 2025];
  it('reaches every town of the region from the home town in every year (by road where the trains do not go)', () => {
    for (const year of years) for (const [x, y] of cells) {
      const r = planRoute(W, '0,0', `${x},${y}`, year, 7 * 60);
      expect(isRoute(r), `${year} ${x},${y}: ${!isRoute(r) ? r.reason : ''}`).toBe(true);
      if (!isRoute(r)) continue;
      // legs chain in time and place
      for (let i = 0; i < r.legs.length; i++) {
        const l = r.legs[i]!;
        expect(l.arrive).toBeGreaterThanOrEqual(l.depart);
        if (i) { expect(l.from).toBe(r.legs[i - 1]!.to); expect(l.depart).toBeGreaterThanOrEqual(r.legs[i - 1]!.arrive - 1e-6); }
      }
      // a town with passenger trains is reached by train to its own station
      if (`${x},${y}` !== '0,0' && stationAt(W, x, y, year).status === 'passenger' && stationAt(W, 0, 0, year).status === 'passenger') {
        expect(r.legs.every((l) => l.mode === 'train'), `${year} ${x},${y}`).toBe(true);
      }
    }
  });
  it('rides through closed stations but changes only at open ones, on the year\'s passenger lines', () => {
    for (const year of [1955, 1990]) for (const [x, y] of cells) {
      const r = planRoute(W, '-2,1', `${x},${y}`, year, 9 * 60);
      if (!isRoute(r)) continue;
      const trains = r.legs.filter((l) => l.mode === 'train');
      for (const l of trains) {
        const [fx, fy] = l.from.split(',').map(Number) as [number, number];
        expect(stationAt(W, fx, fy, year).status).toBe('passenger');
        if (year >= PASSENGER_SERVICE.from) expect(l.line).not.toMatch(/b:/);
        expect(l.km).toBeGreaterThan(0);
      }
      expect(r.changes).toBe(Math.max(0, trains.length - 1));
    }
  });
  it('finds towns by name, nearest first, and searches by prefix', () => {
    const s = railSite(W, 2, -1);
    expect(findTown(W, s.name.toUpperCase(), { cx: 2, cy: -1 })?.id).toBe(s.id);
    expect(findTown(W, 'No Such Town Anywhere')).toBeNull();
    expect(searchTowns(W, s.name.slice(0, 3), { cx: 0, cy: 0 }).map((t) => t.id)).toContain(s.id);
    const r = planRoute(W, railSite(W, 0, 0).name, s.name, 1955);
    expect(isRoute(r) && r.to).toBe(s.id);
  });
  it('every town an NPC can name (the town itself, its legend\'s town) can be reached from where it is told', () => {
    for (const [x, y] of cells) {
      const origin = siteAt(W.seed, x, y);
      const lore = loreOf(W.seed, origin);
      for (const year of [lore.year, 1950, 1980, 2025]) {
        const there = planRoute(W, origin.id, lore.targetId, year, 8 * 60);
        expect(isRoute(there), `${origin.name} -> ${lore.targetName} in ${year}`).toBe(true);
        const back = planRoute(W, lore.targetId, origin.id, year, 8 * 60);
        expect(isRoute(back)).toBe(true);
        // by name too, from the teller's town (names are found nearest the asker)
        const byName = planRoute(W, origin.name, lore.targetName, year, 8 * 60, { cx: x, cy: y });
        expect(isRoute(byName) && byName.to).toBe(lore.targetId);
      }
    }
  });
});
