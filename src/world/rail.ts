import { archetypeOf } from '../sim/archetype';
import { rand } from '../sim/rng';
import { siteAt, type TownSite } from './map';
import { townFounded, townName } from './names';

// THE RAILROADS. Lines join the town sites of the world map (src/world/map.ts), pure in the world seed: the same seed
// always has the same railroads, the same stations and the same timetables, so a town an NPC names is a place the
// player can find on the network and ride to. Everything here is cheap (no town plans): the network, the history of
// every line and station by year, the timetables and the route planner work from the sites alone. Where the track
// runs inside a town is src/town/rail.ts (that needs the town's plan).
//
// The shape of the network (docs: kotlin-street/docs/RAIL.md):
// - MAIN LINES run east-west along every even row of cells (beside the roads that join the towns, map.ts linkRoad) and
//   north-south along one column in every block of four: an endless grid, never abandoned, freight to this day.
// - BRANCH LINES: east-west links on the odd rows (most, not all), north-south spurs that bring an odd-row town with no
//   line of its own to the main line, and a line both ways through every town named "Junction" (on two lines, as its
//   name says). Built 1878-1915; passenger trains end 1950-1969 (a few in the 1930s-40s); about half are abandoned and
//   lifted later, and many of those become rail trails.
// - PASSENGERS: private railroads until 1970 (each line's company), stations closing through the 1950s and 60s; from 1971
//   the national passenger service (PASSENGER_SERVICE, a made-up name) runs on the main lines only and stops at fewer
//   places: the bigger towns and some others; a few stops are dropped later and a few come back (commuter revivals).

/** The world the network is laid over: its seed, and optionally the seed of the town standing at cell (0,0) instead of
 * the one the map would put there (the Kotlin app's hand-picked town, kotlin-street/tools/town-config.ts). */
export interface RailWorld { seed: string; home?: string }

/** The national passenger service, from 1971 (a made-up name: no real railroad). */
export const PASSENGER_SERVICE = { name: 'Pennant', full: 'Pennant National Passenger Service', from: 1971 } as const;

/** Metres per tile of position in the 3D town (1.24 m a tile of art x k 1.693: tools/town-graph.ts SCALE); world distances
 * along the track are tiles x this. */
export const METERS_PER_TILE = 2.1;
/** Minutes to change trains at a station. */
export const TRANSFER_MIN = 5;
/** What a change of trains weighs in the route planner (minutes): routes are the quickest with changes counted this much. */
const CHANGE_PENALTY = 45;

export type TownRank = 'small' | 'town' | 'major';
export type LinkKind = 'main' | 'branch';
export type LinkDir = 'ew' | 'ns';

export interface RailLink {
  /** `ew:cx,cy` joins (cx,cy) to (cx+1,cy); `ns:cx,cy` joins (cx,cy) to (cx,cy+1). */
  id: string;
  a: string;
  b: string;
  dir: LinkDir;
  kind: LinkKind;
  /** The line it belongs to (trains run along a line: `ew:<cy>`, `ewb:<cy>`, `ns:<cx>`, `nsb:<cx>`). */
  line: string;
  built: number;
  /** The last year passenger trains ran on it (undefined: they still do; main lines). */
  passengerEnd?: number;
  /** The year the track was lifted (undefined: still there). */
  abandoned?: number;
  /** The year its bed opened as a rail trail. */
  trail?: number;
  /** The railroad company that built it and ran it until 1971. */
  company: string;
}

/** A town site of the rail world (siteAt, with the world's home town at cell 0,0). */
export function railSite(w: RailWorld, cx: number, cy: number): TownSite {
  const s = siteAt(w.seed, cx, cy);
  if (w.home === undefined || cx !== 0 || cy !== 0) return s;
  return { ...s, seed: w.home, name: townName(w.home), founded: townFounded(w.home) };
}

const cellOf = (id: string): [number, number] => id.split(',').map(Number) as [number, number];
export const siteById = (w: RailWorld, id: string): TownSite => railSite(w, ...cellOf(id));
const odd = (n: number): boolean => ((n % 2) + 2) % 2 === 1;
const isJunctionName = (s: TownSite): boolean => /\bJunction\b/.test(s.name);

/** How big a place the railroad saw in it (not the town's population: a fixed character from its seed). */
export function townRank(s: TownSite): TownRank {
  const a = archetypeOf(s.seed).id;
  const bonus = a === 'mill' || a === 'harbor' || a === 'mining' ? 0.15 : a === 'farm' ? -0.1 : a === 'resort' ? 0.05 : 0;
  const r = rand(s.seed, 'rail-rank') + bonus;
  const rank: TownRank = r > 0.8 ? 'major' : r > 0.42 ? 'town' : 'small';
  return rank === 'small' && isJunctionName(s) ? 'town' : rank;
}

/** The column of the north-south main line in the block of four columns holding [cx]. */
function mainColumn(w: RailWorld, cx: number): number {
  const b = Math.floor(cx / 4);
  return b * 4 + Math.floor(rand(w.seed, 'rail-ns-col', b) * 4);
}

const ewBranch = (w: RailWorld, cx: number, cy: number): boolean => rand(w.seed, 'rail-ew-branch', cx, cy) < 0.7;
/** An odd-row town with no east-west line: its spur to the main line (-1 north, +1 south), or 0 for none. */
function spurOf(w: RailWorld, cx: number, cy: number): -1 | 0 | 1 {
  if (!odd(cy) || ewBranch(w, cx, cy) || ewBranch(w, cx - 1, cy)) return 0;
  if (mainColumn(w, cx) === cx) return 0;
  const r = rand(w.seed, 'rail-spur', cx, cy);
  return r < 0.375 ? -1 : r < 0.75 ? 1 : 0;
}

const DIRS = ['Western', 'Northern', 'Southern', 'Eastern', 'Central', 'Pacific', 'Atlantic', 'Valley', 'Midland', 'Great Lakes'];
const firstWord = (n: string): string => { const p = n.split(' '); return (p[0] === 'Port' || p[0] === 'Mount') && p[1] ? p[1] : p[0]!; };

function companyOf(w: RailWorld, line: string, at: number, kind: LinkKind): string {
  // a company per eight cells of a line (railroads were regional), named after a town on its stretch
  const sect = Math.floor(at / 8);
  const [d, idx] = line.split(':') as [string, string];
  const n = Number(idx);
  const mid = sect * 8 + 3;
  const s = d.startsWith('ew') ? railSite(w, mid, n) : railSite(w, n, mid);
  const tail = DIRS[Math.floor(rand(w.seed, 'rail-co', line, sect) * DIRS.length)]!;
  return kind === 'main' ? `${firstWord(s.name)} & ${tail} Railroad` : `${firstWord(s.name)} Valley Railway`;
}

function history(w: RailWorld, id: string, kind: LinkKind, a: TownSite, b: TownSite): Pick<RailLink, 'built' | 'passengerEnd' | 'abandoned' | 'trail'> {
  const r = (k: string): number => rand(w.seed, 'rail-hist', id, k);
  const late = Math.max(a.founded, b.founded) + 1;
  if (kind === 'main') return { built: Math.max(1852 + Math.floor(r('built') * 38), late) };
  const built = Math.max(1878 + Math.floor(r('built') * 37), late);
  // passenger trains on a branch end in the 1950s-60s (a few already in the 1930s-40s), and by 1971 at the latest
  const passengerEnd = r('early') < 0.15 ? 1934 + Math.floor(r('pe') * 15) : 1950 + Math.floor(r('pe') * 20);
  if (r('aband') >= 0.55) return { built, passengerEnd };
  const abandoned = Math.min(2016, passengerEnd + 3 + Math.floor(r('ay') * 30));
  const trail = r('trail') < 0.6 ? abandoned + 4 + Math.floor(r('ty') * 20) : undefined;
  return { built, passengerEnd, abandoned, ...(trail !== undefined && trail <= 2025 ? { trail } : {}) };
}

function makeLink(w: RailWorld, dir: LinkDir, cx: number, cy: number, kind: LinkKind): RailLink {
  const id = `${dir}:${cx},${cy}`;
  const a = railSite(w, cx, cy), b = dir === 'ew' ? railSite(w, cx + 1, cy) : railSite(w, cx, cy + 1);
  const line = dir === 'ew' ? `ew${kind === 'main' ? '' : 'b'}:${cy}` : `ns${kind === 'main' ? '' : 'b'}:${cx}`;
  return { id, a: a.id, b: b.id, dir, kind, line, ...history(w, id, kind, a, b), company: companyOf(w, line, dir === 'ew' ? cx : cy, kind) };
}

/** The line from (cx,cy) east to (cx+1,cy), if one was ever built. */
export function ewLink(w: RailWorld, cx: number, cy: number): RailLink | null {
  if (!odd(cy)) return makeLink(w, 'ew', cx, cy, 'main');
  if (ewBranch(w, cx, cy)) return makeLink(w, 'ew', cx, cy, 'branch');
  return null;
}

/** The line from (cx,cy) south to (cx,cy+1), if one was ever built. */
export function nsLink(w: RailWorld, cx: number, cy: number): RailLink | null {
  if (mainColumn(w, cx) === cx) return makeLink(w, 'ns', cx, cy, 'main');
  if (isJunctionName(railSite(w, cx, cy)) || isJunctionName(railSite(w, cx, cy + 1))) return makeLink(w, 'ns', cx, cy, 'branch');
  if (spurOf(w, cx, cy) === 1 || spurOf(w, cx, cy + 1) === -1) return makeLink(w, 'ns', cx, cy, 'branch');
  if (rand(w.seed, 'rail-ns-extra', cx, cy) < 0.06) return makeLink(w, 'ns', cx, cy, 'branch');
  return null;
}

/** Every line ever built into the town at (cx,cy): east, west, south, north. */
export function linksAt(w: RailWorld, cx: number, cy: number): RailLink[] {
  return [ewLink(w, cx, cy), ewLink(w, cx - 1, cy), nsLink(w, cx, cy), nsLink(w, cx, cy - 1)].filter((l): l is RailLink => l !== null);
}

/** The other end of [l] from town [id]. */
export const otherEnd = (l: RailLink, id: string): string => (l.a === id ? l.b : l.a);

export const trackIn = (l: RailLink, year: number): boolean => l.built <= year && (l.abandoned === undefined || year < l.abandoned);
export const passengersOn = (l: RailLink, year: number): boolean => trackIn(l, year) && (l.passengerEnd === undefined || year <= l.passengerEnd);

// ---------------------------------------------------------------- stations

export type StationStatus = 'none' | 'passenger' | 'freight' | 'gone';

export interface Station {
  id: string;
  name: string;
  year: number;
  /** none: no railroad (yet, or ever); passenger: trains stop for passengers; freight: track and freight only, the
   * depot shut to passengers (or reused); gone: the rails are lifted. */
  status: StationStatus;
  /** Who runs the passenger trains that stop here (a company before 1971, then the national service). */
  service: string | null;
  /** The lines through it this year (track in place). */
  lines: string[];
  /** It is on two lines or more (a junction). */
  junction: boolean;
  /** A rail trail runs on an old line out of town. */
  trail: boolean;
  /** The year the depot was built (the first line's), if ever. */
  built?: number;
}

interface StationFacts {
  /** A main-line stop shut to passengers before 1971 (undefined: never). */
  closes?: number;
  /** A national stop from 1971. */
  national: boolean;
  /** A national stop dropped in this year. */
  dropped?: number;
  /** Passenger trains come back (a commuter revival) in this year. */
  revived?: number;
}

function stationFacts(w: RailWorld, s: TownSite): StationFacts {
  const r = (k: string): number => rand(w.seed, 'rail-stn', s.id, k);
  const rank = townRank(s);
  const pClose = rank === 'small' ? 0.75 : rank === 'town' ? 0.35 : 0;
  const closes = r('close') < pClose ? 1953 + Math.floor(r('cy') * 17) : undefined;
  const national = rank === 'major' || (rank === 'town' && closes === undefined && r('nat') < 0.45);
  const dropped = national && rank !== 'major' && r('drop') < 0.2 ? 1979 + Math.floor(r('dy') * 18) : undefined;
  const pRevive = archetypeOf(s.seed).id === 'suburb' ? 0.35 : 0.1;
  const revived = (!national || dropped !== undefined) && r('rev') < pRevive ? Math.max(1988 + Math.floor(r('ry') * 30), (dropped ?? 0) + 6) : undefined;
  return { ...(closes !== undefined ? { closes } : {}), national, ...(dropped !== undefined ? { dropped } : {}), ...(revived !== undefined && revived <= 2025 ? { revived } : {}) };
}

/** Is there a path of passenger-carrying lines from [id] to a main line (pre-1971 branch stations need one)? */
function reachesMain(w: RailWorld, id: string, year: number): boolean {
  const seen = new Set([id]);
  let edge = [id];
  for (let step = 0; step < 16 && edge.length; step++) {
    const next: string[] = [];
    for (const t of edge) {
      for (const l of linksAt(w, ...cellOf(t))) {
        if (!passengersOn(l, year)) continue;
        if (l.kind === 'main') return true;
        const o = otherEnd(l, t);
        if (!seen.has(o)) { seen.add(o); next.push(o); }
      }
    }
    edge = next;
  }
  return false;
}

/** The station of the town at (cx,cy) in [year]. */
export function stationAt(w: RailWorld, cx: number, cy: number, year: number): Station {
  const s = railSite(w, cx, cy);
  const all = linksAt(w, cx, cy);
  const built = all.length ? Math.min(...all.map((l) => l.built)) : undefined;
  const track = all.filter((l) => trackIn(l, year));
  const trail = all.some((l) => l.trail !== undefined && l.trail <= year);
  const lines = [...new Set(track.map((l) => l.line))];
  const base = { id: s.id, name: s.name, year, lines, junction: lines.length >= 2, trail, ...(built !== undefined ? { built } : {}) };
  if (built === undefined || built > year) return { ...base, status: 'none', service: null };
  if (!track.length) return { ...base, status: 'gone', service: null };
  return passengerStop(w, s, track, year) ? { ...base, status: 'passenger', service: serviceAt(w, s, track, year) } : { ...base, status: 'freight', service: null };
}

function passengerStop(w: RailWorld, s: TownSite, track: RailLink[], year: number): boolean {
  const f = stationFacts(w, s);
  const main = track.some((l) => l.kind === 'main');
  // (where two main lines cross, the station is where everyone changes trains: it never shuts)
  const mainJunction = track.some((l) => l.kind === 'main' && l.dir === 'ew') && track.some((l) => l.kind === 'main' && l.dir === 'ns');
  if (year >= PASSENGER_SERVICE.from) {
    if (!main) return false;
    if (mainJunction) return true;
    if (f.revived !== undefined && year >= f.revived) return true;
    return f.national && (f.dropped === undefined || year < f.dropped);
  }
  if (!track.some((l) => passengersOn(l, year))) return false;
  if (main) {
    // a main-line town's station shuts on its own date, unless a branch's trains still call there
    // (a junction of passenger lines stays open for the changes; any other station shuts on its own date, unless a
    // branch's trains still call there)
    const lines = new Set(track.filter((l) => passengersOn(l, year)).map((l) => l.line));
    if (f.closes !== undefined && year >= f.closes && lines.size < 2) return track.some((l) => l.kind === 'branch' && passengersOn(l, year)) && reachesMain(w, s.id, year);
    return true;
  }
  return reachesMain(w, s.id, year);
}

function serviceAt(w: RailWorld, s: TownSite, track: RailLink[], year: number): string {
  if (year >= PASSENGER_SERVICE.from) return PASSENGER_SERVICE.full;
  const l = track.find((x) => x.kind === 'main' && passengersOn(x, year)) ?? track.find((x) => passengersOn(x, year))!;
  return l.company;
}

/** What happened to the town's railroad, year by year (for the depot's plaque, the NPCs' fact sheets and the docs). */
export function stationEvents(w: RailWorld, cx: number, cy: number): { year: number; text: string }[] {
  const s = railSite(w, cx, cy);
  const out: { year: number; text: string }[] = [];
  const name = (l: RailLink): string => { const o = siteById(w, otherEnd(l, s.id)); return `the line to ${o.name}`; };
  for (const l of linksAt(w, cx, cy)) {
    out.push({ year: l.built, text: `The ${l.company} opens ${name(l)}.` });
    if (l.passengerEnd !== undefined && l.passengerEnd < PASSENGER_SERVICE.from) out.push({ year: l.passengerEnd + 1, text: `The last passenger train runs on ${name(l)}.` });
    if (l.abandoned !== undefined) out.push({ year: l.abandoned, text: `The rails of ${name(l)} are lifted.` });
    if (l.trail !== undefined) out.push({ year: l.trail, text: `The old bed of ${name(l)} opens as a rail trail.` });
  }
  let was: StationStatus = 'none';
  for (let y = 1900; y <= 2025; y++) {
    const st = stationAt(w, cx, cy, y).status;
    if (st !== was) {
      if (st === 'passenger' && was === 'freight') out.push({ year: y, text: y >= PASSENGER_SERVICE.from ? `Passenger trains stop again (${PASSENGER_SERVICE.name}).` : 'Passenger trains stop again.' });
      if (st === 'freight' && was === 'passenger') out.push({ year: y, text: 'The depot closes to passengers; freight trains still pass.' });
      if (y === PASSENGER_SERVICE.from && st === 'passenger') out.push({ year: y, text: `${PASSENGER_SERVICE.full} takes over the passenger trains.` });
      was = st;
    } else if (y === PASSENGER_SERVICE.from && st === 'passenger') out.push({ year: y, text: `${PASSENGER_SERVICE.full} takes over the passenger trains.` });
  }
  return out.sort((a, b) => a.year - b.year);
}

// ---------------------------------------------------------------- geometry at world scale

export interface Pt { x: number; y: number }

/** Where a line of [dir] passes through the town (world tiles): beside its centre, off to one side (a north or south
 * edge for an east-west line, a west or east edge for a north-south one). The track inside the town is src/town/rail.ts. */
export function linePoint(w: RailWorld, s: TownSite, dir: LinkDir): Pt {
  const side = rand(w.seed, 'rail-side', dir, s.id) < 0.5 ? -1 : 1;
  const off = side * Math.round(dir === 'ew' ? 50 + rand(w.seed, 'rail-off', dir, s.id) * 40 : 60 + rand(w.seed, 'rail-off', dir, s.id) * 80);
  return dir === 'ew' ? { x: s.center.x, y: s.center.y + off } : { x: s.center.x + off, y: s.center.y };
}

/** The surveyed route of [l] between its towns' line points (world tiles): a smooth S of a valley route, square to the
 * line at both ends; [n] segments. */
export function alignment(w: RailWorld, l: RailLink, n = 32): Pt[] {
  const a = siteById(w, l.a), b = siteById(w, l.b);
  const p0 = linePoint(w, a, l.dir), p3 = linePoint(w, b, l.dir);
  const ew = l.dir === 'ew';
  const d = ew ? p3.x - p0.x : p3.y - p0.y;
  // (the curve keeps to the line's own direction at both ends: a mild valley bend between the towns)
  const bow = (rand(w.seed, 'rail-bow', l.id) - 0.5) * 0.12 * d;
  const p1 = ew ? { x: p0.x + d * 0.45, y: p0.y + bow } : { x: p0.x + bow, y: p0.y + d * 0.45 };
  const p2 = ew ? { x: p3.x - d * 0.45, y: p3.y + bow } : { x: p3.x + bow, y: p3.y - d * 0.45 };
  const out: Pt[] = [];
  for (let i = 0; i <= n; i++) {
    const t = i / n, u = 1 - t;
    out.push({ x: u * u * u * p0.x + 3 * u * u * t * p1.x + 3 * u * t * t * p2.x + t * t * t * p3.x, y: u * u * u * p0.y + 3 * u * u * t * p1.y + 3 * u * t * t * p2.y + t * t * t * p3.y });
  }
  return out;
}

export const pathLength = (p: readonly Pt[]): number => p.reduce((s, q, i) => (i ? s + Math.hypot(q.x - p[i - 1]!.x, q.y - p[i - 1]!.y) : 0), 0);

const lengthCache = new Map<string, number>();
/** The length of [l] along the track, in tiles (its alignment). */
export function linkLength(w: RailWorld, l: RailLink): number {
  const k = `${w.seed}|${w.home ?? ''}|${l.id}`;
  let v = lengthCache.get(k);
  if (v === undefined) { v = pathLength(alignment(w, l)); lengthCache.set(k, v); }
  return v;
}

export const tilesToKm = (t: number): number => (t * METERS_PER_TILE) / 1000;

// ---------------------------------------------------------------- timetables

/** Trains a day each way on a line of [kind] in [year]. */
export function trainsPerDay(kind: LinkKind, year: number): number {
  if (kind === 'main') return year < 1950 ? 6 : year < 1960 ? 5 : year < PASSENGER_SERVICE.from ? 3 : year < 2000 ? 2 : 3;
  return year < 1950 ? 3 : year < 1960 ? 2 : 1;
}

/** The trains' average speed, stops included (km/h): steam, then diesel, then the national service, then modern trains. */
export function trainSpeed(kind: LinkKind, year: number): number {
  const v = year < 1950 ? 45 : year < PASSENGER_SERVICE.from ? 60 : year < 2000 ? 75 : 95;
  return kind === 'main' ? v : v * 0.7;
}

/** What pulls the trains in [year] (for the 3D trains and the docs). */
export type TrainEra = 'steam' | 'diesel' | 'national' | 'modern';
export function trainEra(year: number, kind: LinkKind = 'main'): TrainEra {
  if (year < 1950) return 'steam';
  // steam fades out through the 1950s: the branches keep it longest
  if (year < 1957 || (kind === 'branch' && year < 1959)) return year < 1953 || kind === 'branch' ? 'steam' : 'diesel';
  if (year < PASSENGER_SERVICE.from) return 'diesel';
  return year < 2000 ? 'national' : 'modern';
}

/** The first and last departure of the day anywhere on a line (minutes after midnight). */
export const SERVICE_FROM = 5 * 60 + 30;
export const SERVICE_HOURS = 17;

const lineKind = (line: string): LinkKind => (line.includes('b:') ? 'branch' : 'main');
const lineIndex = (line: string): number => Number(line.split(':')[1]);

/** The link of [line] that starts at position [i] (cx for an east-west line, cy for a north-south one). */
function lineLink(w: RailWorld, line: string, i: number): RailLink | null {
  const idx = lineIndex(line);
  const l = line.startsWith('ew') ? ewLink(w, i, idx) : nsLink(w, idx, i);
  return l && l.line === line ? l : null;
}

const cumCache = new Map<string, Map<number, number>>();
/** Tiles along [line] from its position 0 to position [i] (negative for positions before 0). */
function along(w: RailWorld, line: string, i: number): number {
  const key = `${w.seed}|${w.home ?? ''}|${line}`;
  let m = cumCache.get(key);
  if (!m) { m = new Map([[0, 0]]); cumCache.set(key, m); }
  const hit = m.get(i);
  if (hit !== undefined) return hit;
  // walk from the nearest known position (the line's links' lengths, existing or not: positions on an endless line)
  let j = 0;
  for (const k of m.keys()) if (Math.abs(k - i) < Math.abs(j - i)) j = k;
  let s = m.get(j)!;
  const len = (p: number): number => { const idx = lineIndex(line); return linkLength(w, line.startsWith('ew') ? makeLink(w, 'ew', p, idx, lineKind(line)) : makeLink(w, 'ns', idx, p, lineKind(line))); };
  while (j < i) { s += len(j); j++; m.set(j, s); }
  while (j > i) { j--; s -= len(j); m.set(j, s); }
  return s;
}

const minutesFor = (tiles: number, kind: LinkKind, year: number): number => (tilesToKm(tiles) / trainSpeed(kind, year)) * 60;

/**
 * The departures of [line] at position [i] heading [dir] (+1 east/south, -1 west/north) in [year], minutes after
 * midnight, earliest first. Each train runs the line at the year's speed, so a train leaving one station is the
 * departure at the next one exactly a link's running time later (minus the day's wrap).
 */
export function departures(w: RailWorld, line: string, i: number, dir: 1 | -1, year: number): number[] {
  const kind = lineKind(line);
  const n = trainsPerDay(kind, year);
  const head = (SERVICE_HOURS * 60) / n;
  const phase = rand(w.seed, 'rail-phase', line, dir, year >= PASSENGER_SERVICE.from ? 'n' : 'p') * head;
  const off = dir * minutesFor(along(w, line, i), kind, year);
  const first = SERVICE_FROM + ((((phase + off) % head) + head) % head);
  return Array.from({ length: n }, (_, k) => Math.round((first + k * head) * 10) / 10);
}

/** The next departure at or after [t] (minutes, may run into later days). */
function nextDeparture(w: RailWorld, line: string, i: number, dir: 1 | -1, year: number, t: number): number {
  const day = Math.floor(t / 1440);
  for (const d of [day, day + 1, day + 2]) for (const dep of departures(w, line, i, dir, year)) if (d * 1440 + dep >= t - 1e-6) return d * 1440 + dep;
  return Infinity;
}

// ---------------------------------------------------------------- fares

/** What a ticket costs in [year] for [km] (dollars of that year): a minimum fare, and so much a km past it. */
export function fare(year: number, km: number): number {
  const pts: [number, number, number][] = [[1900, 0.1, 0.012], [1950, 0.25, 0.02], [1971, 1, 0.045], [1990, 3, 0.09], [2025, 6, 0.16]];
  let lo = pts[0]!, hi = pts[pts.length - 1]!;
  for (let i = 0; i + 1 < pts.length; i++) if (year >= pts[i]![0] && year <= pts[i + 1]![0]) { lo = pts[i]!; hi = pts[i + 1]!; break; }
  if (year > hi[0]) lo = hi;
  const t = hi[0] === lo[0] ? 0 : (Math.min(year, hi[0]) - lo[0]) / (hi[0] - lo[0]);
  const min = lo[1] + (hi[1] - lo[1]) * t, perKm = lo[2] + (hi[2] - lo[2]) * t;
  return Math.round(Math.max(min, perKm * km) * 100) / 100;
}

// ---------------------------------------------------------------- finding towns and planning routes

/** The town called [query] (a name, any case, or an id "cx,cy") nearest cell [near], searching [radius] cells round it. */
export function findTown(w: RailWorld, query: string, near: { cx: number; cy: number } = { cx: 0, cy: 0 }, radius = 8): TownSite | null {
  const q = query.trim().toLowerCase();
  if (/^-?\d+,-?\d+$/.test(q)) return siteById(w, q);
  for (let r = 0; r <= radius; r++) {
    let best: TownSite | null = null, bd = Infinity;
    for (let cy = near.cy - r; cy <= near.cy + r; cy++) for (let cx = near.cx - r; cx <= near.cx + r; cx++) {
      if (Math.max(Math.abs(cx - near.cx), Math.abs(cy - near.cy)) !== r) continue;
      const s = railSite(w, cx, cy);
      if (s.name.toLowerCase() !== q) continue;
      const d = Math.hypot(cx - near.cx, cy - near.cy);
      if (d < bd) { best = s; bd = d; }
    }
    if (best) return best;
  }
  return null;
}

/** Town names starting with [prefix] (any case) within [radius] cells of [near], nearest first: the ticket window's search. */
export function searchTowns(w: RailWorld, prefix: string, near: { cx: number; cy: number }, radius = 6, limit = 12): TownSite[] {
  const q = prefix.trim().toLowerCase();
  const out: { s: TownSite; d: number }[] = [];
  for (let cy = near.cy - radius; cy <= near.cy + radius; cy++) for (let cx = near.cx - radius; cx <= near.cx + radius; cx++) {
    const s = railSite(w, cx, cy);
    if (s.name.toLowerCase().startsWith(q) || s.name.toLowerCase().includes(` ${q}`)) out.push({ s, d: Math.hypot(cx - near.cx, cy - near.cy) });
  }
  return out.sort((a, b) => a.d - b.d).slice(0, limit).map((o) => o.s);
}

export interface RouteLeg {
  mode: 'train' | 'road';
  from: string;
  fromName: string;
  to: string;
  toName: string;
  /** Minutes after midnight of the day of travel (past 1440: the next day). */
  depart: number;
  arrive: number;
  km: number;
  /** The train's line and who runs it (train legs). */
  line?: string;
  operator?: string;
  /** Stations the train calls at between [from] and [to]. */
  stops?: string[];
  /** The towns the train passes through (ids, in order, ends included): the track to follow for a ride. */
  via?: string[];
}

export interface Route {
  from: string;
  to: string;
  year: number;
  legs: RouteLeg[];
  depart: number;
  arrive: number;
  /** Train changes. */
  changes: number;
  km: number;
  /** The rail fare (dollars of [year]); road legs are paid on the bus. */
  fare: number;
  notes: string[];
}

/** No route: why, and the nearest one can get. */
export interface NoRoute { from: string; to: string; year: number; reason: string; nearest?: string }

const ROAD_FACTOR = 1.3;
const ROAD_KMH = 40;
const ROAD_WAIT = 15;

/** The nearest town with passenger trains in [year] to [s] by road (rings of cells out to [radius]), with the road's km. */
export function nearestStation(w: RailWorld, s: TownSite, year: number, radius = 4): { site: TownSite; km: number } | null {
  let best: { site: TownSite; km: number } | null = null;
  for (let r = 0; r <= radius; r++) {
    for (let cy = s.cy - r; cy <= s.cy + r; cy++) for (let cx = s.cx - r; cx <= s.cx + r; cx++) {
      if (Math.max(Math.abs(cx - s.cx), Math.abs(cy - s.cy)) !== r) continue;
      if (stationAt(w, cx, cy, year).status !== 'passenger') continue;
      const o = railSite(w, cx, cy);
      const km = tilesToKm(Math.hypot(o.center.x - s.center.x, o.center.y - s.center.y) * ROAD_FACTOR);
      if (!best || km < best.km) best = { site: o, km };
    }
    if (best && r >= 1) return best; // (one more ring could only be nearer diagonally; a ring past the first find is enough)
  }
  return best;
}

const roadLeg = (a: TownSite, b: TownSite, km: number, depart: number): RouteLeg => ({
  mode: 'road', from: a.id, fromName: a.name, to: b.id, toName: b.name, depart, arrive: depart + ROAD_WAIT + Math.round((km / ROAD_KMH) * 60), km: Math.round(km * 10) / 10,
});

interface Edge { link: RailLink; to: string; line: string; dir: 1 | -1; pos: number }

function edgesFrom(w: RailWorld, id: string, year: number): Edge[] {
  const [cx, cy] = cellOf(id);
  const out: Edge[] = [];
  for (const l of linksAt(w, cx, cy)) {
    if (!passengersOn(l, year)) continue;
    // national-era trains run on the main lines only
    if (year >= PASSENGER_SERVICE.from && l.kind !== 'main') continue;
    const fwd = l.a === id;
    out.push({ link: l, to: otherEnd(l, id), line: l.line, dir: fwd ? 1 : -1, pos: l.dir === 'ew' ? cx : cy });
  }
  return out;
}

/**
 * The quickest way from town [from] to town [to] (ids or names) in [year], leaving at [time] (minutes after midnight):
 * the trains over the year's passenger network (earliest arrival, changes at junction stations, the timetables of
 * [departures]), with a road leg (bus or car) at either end when a town has no passenger trains that year. Pure.
 * Names are looked up nearest [near] (the player's cell) for [from], and nearest [from] for [to].
 */
export function planRoute(w: RailWorld, from: string, to: string, year: number, time = 8 * 60, near: { cx: number; cy: number } = { cx: 0, cy: 0 }): Route | NoRoute {
  // (names repeat across the endless world: [from] is the one nearest [near], [to] the one nearest [from])
  const A = /^-?\d+,-?\d+$/.test(from) ? siteById(w, from) : findTown(w, from, near);
  const B = !A ? null : /^-?\d+,-?\d+$/.test(to) ? siteById(w, to) : findTown(w, to, { cx: A.cx, cy: A.cy });
  if (!A || !B) return { from, to, year, reason: `No town called ${!A ? from : to} nearby.` };
  const notes: string[] = [];
  const legs: RouteLeg[] = [];
  if (A.id === B.id) return { from: A.id, to: B.id, year, legs, depart: time, arrive: time, changes: 0, km: 0, fare: 0, notes: ['You are already there.'] };
  let t = time;
  let start = A, end = B;
  if (stationAt(w, A.cx, A.cy, year).status !== 'passenger') {
    const n = nearestStation(w, A, year);
    if (!n) return { from: A.id, to: B.id, year, reason: `No passenger trains anywhere near ${A.name} in ${year}.` };
    notes.push(`${A.name} has no passenger trains in ${year}: by road to ${n.site.name} first.`);
    start = n.site;
  }
  if (stationAt(w, B.cx, B.cy, year).status !== 'passenger') {
    const n = nearestStation(w, B, year);
    if (!n) return { from: A.id, to: B.id, year, reason: `Passenger trains go nowhere near ${B.name} in ${year}.` };
    notes.push(`Passenger trains do not stop at ${B.name} in ${year}: the nearest station is ${n.site.name}, ${Math.round(n.km * 10) / 10} km by road.`);
    end = n.site;
  }
  if (start.id !== A.id) { const l = roadLeg(A, start, nearestStation(w, A, year)!.km, t); legs.push(l); t = l.arrive; }
  if (start.id !== end.id) {
    const rail = railLegs(w, start, end, year, t);
    if (!rail) {
      // no passenger rail between them: the whole way by road
      const km = tilesToKm(Math.hypot(B.center.x - A.center.x, B.center.y - A.center.y) * ROAD_FACTOR);
      return { from: A.id, to: B.id, year, reason: `No passenger trains run between ${start.name} and ${end.name} in ${year}; it is ${Math.round(km)} km by road.`, nearest: start.name };
    }
    legs.push(...rail);
    t = rail[rail.length - 1]!.arrive;
  }
  if (end.id !== B.id) legs.push(roadLeg(end, B, nearestStation(w, B, year)!.km, t));
  const trains = legs.filter((l) => l.mode === 'train');
  const km = trains.reduce((s, l) => s + l.km, 0);
  return {
    from: A.id, to: B.id, year, legs, depart: legs[0]!.depart, arrive: legs[legs.length - 1]!.arrive, changes: Math.max(0, trains.length - 1),
    km: Math.round(legs.reduce((s, l) => s + l.km, 0) * 10) / 10, fare: trains.length ? fare(year, km) : 0, notes,
  };
}

/** Earliest-arrival search over the year's passenger trains, kept to a box round the two towns. */
function railLegs(w: RailWorld, A: TownSite, B: TownSite, year: number, t0: number): RouteLeg[] | null {
  const pad = 3;
  const x0 = Math.min(A.cx, B.cx) - pad, x1 = Math.max(A.cx, B.cx) + pad, y0 = Math.min(A.cy, B.cy) - pad, y1 = Math.max(A.cy, B.cy) + pad;
  // (ordered by arrival plus a penalty for each change: the same train is better than hopping off and back on, and a
  // traveller takes one change and a short wait over three changes for a few minutes)
  interface St { id: string; line: string; dir: number; t: number; c: number; prev: string | null; edge: Edge | null; dep: number }
  const best = new Map<string, St>();
  const key = (id: string, line: string, dir: number): string => `${id}|${line}|${dir}`;
  const open = new Map<string, boolean>();
  const isOpen = (id: string): boolean => { let v = open.get(id); if (v === undefined) { const [cx, cy] = cellOf(id); v = stationAt(w, cx, cy, year).status === 'passenger'; open.set(id, v); } return v; };
  const heap: St[] = [];
  const push = (s: St): void => {
    const k = key(s.id, s.line, s.dir);
    const b = best.get(k);
    if (b && b.c <= s.c) return;
    best.set(k, s);
    heap.push(s);
    let i = heap.length - 1;
    while (i > 0) { const p = (i - 1) >> 1; if (heap[p]!.c <= heap[i]!.c) break; [heap[p], heap[i]] = [heap[i]!, heap[p]!]; i = p; }
  };
  const pop = (): St => {
    const top = heap[0]!, last = heap.pop()!;
    if (heap.length) {
      heap[0] = last;
      let i = 0;
      for (;;) { const l = 2 * i + 1, r = l + 1; let m = i; if (l < heap.length && heap[l]!.c < heap[m]!.c) m = l; if (r < heap.length && heap[r]!.c < heap[m]!.c) m = r; if (m === i) break; [heap[m], heap[i]] = [heap[i]!, heap[m]!]; i = m; }
    }
    return top;
  };
  push({ id: A.id, line: '', dir: 0, t: t0, c: t0, prev: null, edge: null, dep: t0 });
  let goal: St | null = null;
  while (heap.length) {
    const s = pop();
    if (best.get(key(s.id, s.line, s.dir)) !== s) continue;
    if (s.id === B.id) { goal = s; break; }
    if (s.t - t0 > 3 * 1440) break;
    for (const e of edgesFrom(w, s.id, year)) {
      const [ncx, ncy] = cellOf(e.to);
      if (ncx < x0 || ncx > x1 || ncy < y0 || ncy > y1) continue;
      const stay = e.line === s.line && e.dir === s.dir;
      if (!stay && !isOpen(s.id)) continue;
      const dep = stay ? s.t : nextDeparture(w, e.line, e.pos, e.dir, year, s.t + (s.line ? TRANSFER_MIN : 0));
      if (!Number.isFinite(dep)) continue;
      const arr = dep + minutesFor(linkLength(w, e.link), e.link.kind, year);
      // (one may ride through a closed station, not get off there)
      push({ id: e.to, line: e.line, dir: e.dir, t: arr, c: s.c + (arr - s.t) + (stay ? 0 : CHANGE_PENALTY), prev: key(s.id, s.line, s.dir), edge: e, dep });
    }
  }
  if (!goal) return null;
  // back along the chain, then grouped into legs by train
  const chain: St[] = [];
  for (let s: St | undefined = goal; s && s.edge; s = s.prev ? best.get(s.prev) : undefined) chain.unshift(s);
  const legs: RouteLeg[] = [];
  let cur: { from: string; line: string; dir: number; dep: number; via: string[]; km: number; company: string } | null = null;
  let prevId = A.id;
  let prevArrive = t0;
  const close = (arrive: number, to: string): void => {
    if (!cur) return;
    const f = siteById(w, cur.from), t = siteById(w, to);
    const stops = cur.via.slice(1, -1).filter(isOpen).map((id) => siteById(w, id).name);
    legs.push({ mode: 'train', from: cur.from, fromName: f.name, to, toName: t.name, depart: round1(cur.dep), arrive: round1(arrive), km: Math.round(cur.km * 10) / 10, line: cur.line, operator: year >= PASSENGER_SERVICE.from ? PASSENGER_SERVICE.full : cur.company, stops, via: cur.via });
  };
  for (const s of chain) {
    const e = s.edge!;
    // (a step continues the train ridden when it is the same train: same line and way, leaving the moment it arrived)
    if (!cur || cur.line !== e.line || cur.dir !== e.dir || s.dep !== prevArrive) {
      if (cur) close(prevArrive, prevId);
      cur = { from: prevId, line: e.line, dir: e.dir, dep: s.dep, via: [prevId], km: 0, company: e.link.company };
    }
    cur.via.push(s.id);
    cur.km += tilesToKm(linkLength(w, e.link));
    prevArrive = s.t;
    prevId = s.id;
  }
  close(prevArrive, prevId);
  return legs;
}
const round1 = (v: number): number => Math.round(v * 10) / 10;

/** One line an NPC can say about the town's railroad in [year] (for the dialogue fact sheets). */
export function railFact(w: RailWorld, cx: number, cy: number, year: number): string {
  const st = stationAt(w, cx, cy, year);
  const s = railSite(w, cx, cy);
  const to = (): string => {
    const names = linksAt(w, cx, cy).filter((l) => passengersOn(l, year) && (year < PASSENGER_SERVICE.from || l.kind === 'main')).map((l) => siteById(w, otherEnd(l, s.id)).name);
    return names.length ? ` Trains run to ${names.join(' and ')}.` : '';
  };
  switch (st.status) {
    case 'none': return st.built ? `The railroad has not reached ${s.name} yet.` : `${s.name} never had a railroad; the nearest trains are elsewhere.`;
    case 'gone': return `The railroad through ${s.name} is gone; the rails were lifted${st.trail ? ' and the old line is a rail trail now' : ''}.`;
    case 'freight': return `Freight trains still pass through ${s.name}, but passenger trains no longer stop.`;
    default: return `${s.name} has a passenger station (${st.service}).${to()}`;
  }
}
