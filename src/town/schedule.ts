import { rand } from '../sim/rng';
import { ageIn, fullName, isAlive, type Id, type World } from '../sim/types';
import type { TownLayout } from './layout';
import { nearRoad } from './plan';

// DAILY LIFE. Everyone in town follows a simple, deterministic day built from the simulation:
// sleep at home, walk to work (owners to their own shop, other adults to some shop, children to
// school or the plaza), walk home, and sometimes stroll to the plaza in the evening. Positions are
// a pure function of (person, hour), so nothing drifts and rewriting the past changes who is where.
// While someone is at home or at work they are "inside" (position null).

export interface Tile { x: number; y: number }

export type Activity = 'home' | 'work' | 'commute' | 'plaza';

export interface Resident {
  id: Id;
  name: string;
  age: number;
  sex: 'F' | 'M';
  /** Where they live and where they go in the day (walkable tiles next to the buildings). */
  home: Tile;
  work: Tile;
  /** What they do at `work`, for dialogue and the info card. */
  job: string;
  evening: Tile;
  /** Walking routes, home -> work and home -> plaza. */
  toWork: Tile[];
  toPlaza: Tile[];
  /** Hours (0..24) of the day they leave for work, in the minutes after 7am, etc. */
  leave: number;
  strolls: boolean;
  /** Stable 0..1 for choosing a look. */
  look: number;
}

export interface Pose { x: number; y: number; dir: 0 | 1 | 2 | 3; moving: boolean }

/** Tiles per game hour a person walks; a game day is only minutes long, so this is brisk. */
export const WALK_TILES_PER_HOUR = 55;

export function walkable(layout: TownLayout): (x: number, y: number) => boolean {
  const roads = layout.roads;
  const p = layout.plan.plaza;
  return (x, y) => nearRoad(roads, x, y) || (x >= p.x && x < p.x + p.w && y >= p.y && y < p.y + p.h);
}

/** Shortest walk between two walkable tiles (breadth-first), or just the two ends if unreachable. */
export function findPath(ok: (x: number, y: number) => boolean, a: Tile, b: Tile, width: number, height: number): Tile[] {
  const key = (x: number, y: number): number => y * width + x;
  const from = new Map<number, number>();
  from.set(key(a.x, a.y), -1);
  const queue: Tile[] = [a];
  for (let i = 0; i < queue.length; i++) {
    const t = queue[i]!;
    if (t.x === b.x && t.y === b.y) {
      const path: Tile[] = [];
      for (let k = key(t.x, t.y); k !== -1; k = from.get(k)!) path.push({ x: k % width, y: Math.floor(k / width) });
      return path.reverse();
    }
    for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]] as const) {
      const x = t.x + dx, y = t.y + dy;
      if (x < 0 || y < 0 || x >= width || y >= height || from.has(key(x, y)) || !ok(x, y)) continue;
      from.set(key(x, y), key(t.x, t.y));
      queue.push({ x, y });
    }
  }
  return [a, b];
}

/** The walkable tile closest to the middle of the bottom edge of a building. */
export function doorstep(ok: (x: number, y: number) => boolean, r: { x: number; y: number; w: number; h: number }): Tile | null {
  const cx = Math.floor(r.x + r.w / 2), cy = r.y + r.h;
  for (let d = 0; d <= 10; d++) {
    for (let dy = -d; dy <= d; dy++) for (let dx = -d; dx <= d; dx++) {
      if (Math.max(Math.abs(dx), Math.abs(dy)) !== d) continue;
      if (ok(cx + dx, cy + dy)) return { x: cx + dx, y: cy + dy };
    }
  }
  return null;
}

/** Everyone alive in the town this year, with a home, a workplace and walking routes. */
export function buildResidents(layout: TownLayout, w: World): Resident[] {
  const ok = walkable(layout);
  const { width, height } = layout.plan;
  const homes = new Map<Id, Tile>();
  for (const lot of layout.lots) {
    if (lot.kind !== 'house') continue;
    const t = doorstep(ok, lot);
    if (t) for (const id of lot.residents) homes.set(id, t);
  }
  const shops = layout.lots.filter((l) => l.kind === 'shop' && l.vacantSince === undefined);
  const shopSpot = shops.map((s) => ({ lot: s, tile: doorstep(ok, s) })).filter((s): s is { lot: typeof s.lot; tile: Tile } => s.tile !== null);
  const plazaR = layout.plan.plaza;
  const plazaTile: Tile = { x: Math.floor(plazaR.x + plazaR.w / 2), y: plazaR.y + plazaR.h - 2 };
  const school = layout.landmarks.find((l) => /school/i.test(l.id) || /school/i.test(l.name));
  const schoolTile = school ? doorstep(ok, school) : null;
  const jobOf = new Map<Id, { tile: Tile; job: string }>();
  for (const s of shopSpot) {
    const b = w.businesses[s.lot.refId];
    if (b?.owner) jobOf.set(b.owner, { tile: s.tile, job: `${b.name} (owner)` });
  }

  const out: Resident[] = [];
  for (const [id, home] of [...homes].sort((a, b) => (a[0] < b[0] ? -1 : 1))) {
    const p = w.people[id];
    if (!p || !isAlive(p)) continue;
    const age = ageIn(p, w.year);
    if (age < 5) continue; // toddlers stay indoors
    const R = (...k: (string | number)[]): number => rand(w.seed, 'day', id, ...k);
    let work: { tile: Tile; job: string } | undefined = jobOf.get(id);
    if (!work && age >= 18 && age < 66 && shopSpot.length) {
      const s = shopSpot[Math.floor(R('job') * shopSpot.length)]!;
      work = { tile: s.tile, job: `works at ${w.businesses[s.lot.refId]!.name}` };
    }
    if (!work && age < 18) work = { tile: schoolTile ?? plazaTile, job: schoolTile ? 'at school' : 'out about town' };
    if (!work) work = { tile: plazaTile, job: 'retired' };
    out.push({
      id, name: fullName(p), age, sex: p.sex, home, work: work.tile, job: work.job,
      evening: plazaTile,
      toWork: findPath(ok, home, work.tile, width, height),
      toPlaza: findPath(ok, home, plazaTile, width, height),
      leave: 7 + R('leave') * 1.2,
      strolls: R('stroll') < 0.35,
      look: R('look'),
    });
  }
  return out;
}

const dirOf = (dx: number, dy: number): 0 | 1 | 2 | 3 => (Math.abs(dx) >= Math.abs(dy) ? (dx > 0 ? 0 : 2) : dy > 0 ? 3 : 1);

/** A point `dist` tiles along a path, with the way they are facing. */
function along(path: Tile[], dist: number, reverse: boolean): Pose {
  const pts = reverse ? [...path].reverse() : path;
  let left = Math.max(0, dist);
  for (let i = 0; i + 1 < pts.length; i++) {
    const a = pts[i]!, b = pts[i + 1]!;
    const len = Math.hypot(b.x - a.x, b.y - a.y);
    if (left <= len) {
      const f = len ? left / len : 0;
      return { x: a.x + (b.x - a.x) * f + 0.5, y: a.y + (b.y - a.y) * f + 0.5, dir: dirOf(b.x - a.x, b.y - a.y), moving: true };
    }
    left -= len;
  }
  const e = pts[pts.length - 1]!;
  return { x: e.x + 0.5, y: e.y + 0.5, dir: 3, moving: false };
}

const pathLength = (path: Tile[]): number => {
  let n = 0;
  for (let i = 0; i + 1 < path.length; i++) n += Math.hypot(path[i + 1]!.x - path[i]!.x, path[i + 1]!.y - path[i]!.y);
  return n;
};

/** What this person is doing at `hour` (0..24), and where; null position = indoors. */
export function dayAt(r: Resident, hour: number): { activity: Activity; pose: Pose | null } {
  const walkH = (path: Tile[]): number => pathLength(path) / WALK_TILES_PER_HOUR;
  const out = walkH(r.toWork), leave = r.leave, arrive = leave + out;
  const workEnd = r.age < 18 ? 15.5 : r.age >= 66 ? 12 : 17 + (r.look * 1.5);
  if (hour >= leave && hour < arrive) return { activity: 'commute', pose: along(r.toWork, (hour - leave) * WALK_TILES_PER_HOUR, false) };
  if (hour >= arrive && hour < workEnd) {
    // Retired people sit in the plaza instead of going indoors.
    return { activity: 'work', pose: r.job === 'retired' ? { ...along([r.work], 0, false), moving: false } : null };
  }
  if (hour >= workEnd && hour < workEnd + out) return { activity: 'commute', pose: along(r.toWork, (hour - workEnd) * WALK_TILES_PER_HOUR, true) };
  // Evening stroll to the plaza and back.
  if (r.strolls && hour >= 19 && hour < 21.5) {
    const t = walkH(r.toPlaza);
    if (hour < 19 + t) return { activity: 'commute', pose: along(r.toPlaza, (hour - 19) * WALK_TILES_PER_HOUR, false) };
    if (hour < 20.5) return { activity: 'plaza', pose: { ...along(r.toPlaza, 1e9, false), moving: false } };
    if (hour < 20.5 + t) return { activity: 'commute', pose: along(r.toPlaza, (hour - 20.5) * WALK_TILES_PER_HOUR, true) };
  }
  return { activity: 'home', pose: null };
}
