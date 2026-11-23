import { fare, planRoute, ROAD_WAIT, type Route } from '../rail';
import type { Place } from './places';
import { roadKmh, type Road } from './roads';
import { nextSailing, sailingsPerWeek, type SeaRoute } from './sea';
import type { World } from './world';

// THE MULTIMODAL ROUTE PLANNER (WORLD_GEN.md section 6, "The route planner becomes multimodal"): road legs (a car, or
// the bus at three quarters of a car's speed after a wait), rail legs (src/world/rail.ts planRoute on the skeleton's
// network, with its own road legs to the stations), and sea legs (a ship leg costs its sailing time plus waiting for
// the next sailing: days, not minutes). Times are minutes from the start of week 0 (day 0 is a Monday, 00:00).

export interface TripLeg {
  mode: 'road' | 'train' | 'sea';
  from: string;
  fromName: string;
  to: string;
  toName: string;
  depart: number;
  arrive: number;
  km: number;
  line?: string;
  operator?: string;
  ship?: string;
  route?: string;
}
export interface Trip { from: string; to: string; year: number; legs: TripLeg[]; depart: number; arrive: number; km: number; fare: number; notes: string[] }
export interface NoTrip { from: string; to: string; year: number; reason: string }
export const isTrip = (t: Trip | NoTrip): t is Trip => (t as Trip).legs !== undefined;

export const DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'] as const;
export const dayName = (t: number): string => DAYS[(((Math.floor(t / 1440) % 7) + 7) % 7)]!;
/** Minutes to change at a port (to the ship, off the ship). */
export const PORT_CHANGE = 60;
const BUS = 0.75;

export interface TripOptions { car?: boolean }

function resolve(w: World, q: string): Place | null {
  return w.byId.get(q) ?? w.byName.get(q.trim().toLowerCase()) ?? null;
}

/** The best way between two places on the same landmass leaving at [t]: by road (car or bus) or by train. */
function landTrip(w: World, A: Place, B: Place, year: number, t: number, o: TripOptions): TripLeg[] | null {
  if (A.id === B.id) return [];
  const speed = (r: Road | null, y: number): number => roadKmh(r, y) * (o.car ? 1 : BUS);
  const d = w.roadGraph.drive(A.id, B.id, year, speed);
  const road: TripLeg[] | null = d ? [{ mode: 'road', from: A.id, fromName: A.name, to: B.id, toName: B.name, depart: t, arrive: t + (o.car ? 0 : ROAD_WAIT) + Math.round(d.min), km: d.km }] : null;
  let rail: TripLeg[] | null = null;
  const day = Math.floor(t / 1440) * 1440;
  const r = planRoute(w.railWorld, A.id, B.id, year, t - day);
  if ((r as Route).legs && (r as Route).legs.some((l) => l.mode === 'train')) {
    rail = (r as Route).legs.map((l) => ({ mode: l.mode === 'train' ? 'train' : 'road', from: l.from, fromName: l.fromName, to: l.to, toName: l.toName, depart: day + l.depart, arrive: day + l.arrive, km: l.km, ...(l.line ? { line: l.line } : {}), ...(l.operator ? { operator: l.operator } : {}) }));
  }
  if (!road) return rail;
  if (!rail) return road;
  return rail[rail.length - 1]!.arrive < road[0]!.arrive ? rail : road;
}

/** The quickest way from [from] to [to] (ids or names) in [year], leaving at [time] (minutes from Monday 00:00). */
export function planTrip(w: World, from: string, to: string, year: number, time = 8 * 60, o: TripOptions = {}): Trip | NoTrip {
  const A = resolve(w, from), B = resolve(w, to);
  if (!A || !B) return { from, to, year, reason: `No place called ${!A ? from : to}.` };
  const done = (legs: TripLeg[], notes: string[]): Trip => {
    const rail = legs.filter((l) => l.mode === 'train').reduce((s, l) => s + l.km, 0);
    const sea = legs.filter((l) => l.mode === 'sea').reduce((s, l) => s + l.km, 0);
    return {
      from: A.id, to: B.id, year, legs, depart: legs[0]?.depart ?? time, arrive: legs[legs.length - 1]?.arrive ?? time, notes,
      km: Math.round(legs.reduce((s, l) => s + l.km, 0) * 10) / 10, fare: Math.round(((rail ? fare(year, rail) : 0) + (sea ? fare(year, sea) * 1.5 : 0)) * 100) / 100,
    };
  };
  if (A.mass === B.mass) {
    const legs = landTrip(w, A, B, year, time, o);
    return legs ? done(legs, []) : { from: A.id, to: B.id, year, reason: `No road or railroad joins ${A.name} and ${B.name}.` };
  }
  // ---- across the sea: to a port on A's landmass, ships (earliest arrival over the sailings), from a port on B's
  const ports = new Map<string, Place>();
  const routesAt = new Map<string, SeaRoute[]>();
  for (const r of w.sea.routes) for (const id of [r.a, r.b]) { ports.set(id, w.byId.get(id)!); const a = routesAt.get(id); if (a) a.push(r); else routesAt.set(id, [r]); }
  interface S { t: number; legs: TripLeg[] }
  const best = new Map<string, S>();
  for (const p of ports.values()) {
    if (p.mass !== A.mass) continue;
    const legs = landTrip(w, A, p, year, time, o);
    if (!legs) continue;
    const t = legs.length ? legs[legs.length - 1]!.arrive : time;
    if (!best.has(p.id) || t < best.get(p.id)!.t) best.set(p.id, { t, legs });
  }
  const doneP = new Set<string>();
  for (;;) {
    let cur: string | null = null, ct = Infinity;
    for (const [id, s] of best) if (!doneP.has(id) && s.t < ct) { ct = s.t; cur = id; }
    if (cur === null) break;
    doneP.add(cur);
    const s = best.get(cur)!;
    for (const r of routesAt.get(cur) ?? []) {
      const other = r.a === cur ? r.b : r.a;
      const dep = nextSailing(r, cur, year, s.t + PORT_CHANGE);
      if (!Number.isFinite(dep)) continue;
      const arr = dep + Math.round(r.hours * 60);
      if (arr >= (best.get(other)?.t ?? Infinity)) continue;
      const P = w.byId.get(cur)!, Q = w.byId.get(other)!;
      best.set(other, { t: arr, legs: [...s.legs, { mode: 'sea', from: cur, fromName: P.name, to: other, toName: Q.name, depart: dep, arrive: arr, km: r.km, ship: sailingsPerWeek(r, year).ship ?? 'ship', route: r.id }] });
    }
  }
  let out: S | null = null;
  for (const [id, s] of best) {
    const P = w.byId.get(id)!;
    if (P.mass !== B.mass || !s.legs.some((l) => l.mode === 'sea')) continue;
    const legs = landTrip(w, P, B, year, s.t + PORT_CHANGE, o);
    if (!legs) continue;
    const all = [...s.legs, ...legs];
    const t = all[all.length - 1]!.arrive;
    if (!out || t < out.t) out = { t, legs: all };
  }
  if (!out) return { from: A.id, to: B.id, year, reason: `No ship sails from ${A.name}'s shores towards ${B.name} in ${year}.` };
  const first = out.legs.find((l) => l.mode === 'sea')!;
  return done(out.legs, [`The ${first.ship} sails from ${first.fromName} on ${dayName(first.depart)}.`]);
}
