import { CELL_M, W, wrapDx, wrapX } from './grid';
import type { Place, WorldPin } from './places';
import type { World } from './world';

// THE VECTORS' TRUE PATHS (WORLD_GEN.md 3, "The capital at the start" and "Every hub has the same kind of pins"; phase
// W4): the skeleton's rivers, roads and rail lines are atlas-cell chains; the renderer and the town need polylines that
// meet a hub's authored plan exactly at its pins. Here every vector becomes a polyline in atlas cell units (floats, x
// unwrapped along the line so it never jumps at the seam):
// - a road or rail line that starts or ends at a hub starts or ends exactly on the pin it leaves by (the cell chain's
//   first cell is the hub's own, the second the cell just out past the pin: the hub's cell gives way to the pin and a
//   point half a cell out along the pin's way);
// - a river through a hub with river pins (the capital) is bent through them: its cells inside the pad (and an approach
//   margin) give way to the inflow pin's approach, the inflow pin, the outflow pin and the outflow's approach, so the
//   world river enters the plan where the plan's river column starts and leaves where it ends; a tributary that met the
//   river inside the pad meets the bent river at its nearest point outside the plan.
// Pure in the world; baked into the skeleton (bake.ts, section "pts").

export type Pt = [number, number];

/** How far out past a pin its approach point is (m): the vector leaves the plan straight along the pin's way. */
export const APPROACH_M = 450;

const near = (p: Place, x: number, z: number): { dx: number; dz: number } => ({ dx: wrapDx(x - p.x) * CELL_M, dz: (z - p.z) * CELL_M });
const centre = (c: number): Pt => [(c % W) + 0.5, Math.floor(c / W) + 0.5];
const out = (q: WorldPin, m: number): Pt => [q.x + (q.ox * m) / CELL_M, q.z + (q.oz * m) / CELL_M];

/** Points made continuous in x (no jump across the seam): each x within half the planet of the one before. */
export function unwrap(pts: Pt[]): Pt[] {
  if (!pts.length) return pts;
  const r: Pt[] = [[pts[0]![0], pts[0]![1]]];
  for (let i = 1; i < pts.length; i++) r.push([r[i - 1]![0] + wrapDx(pts[i]![0] - r[i - 1]![0]), pts[i]![1]]);
  return r;
}

/** The pin of [hub] of [kind] nearest the cell chain's next cell [next] (the one the route leaves the pad by). */
function pinFor(hub: Place, kind: 'highway' | 'rail', next: number): WorldPin | null {
  const pins = (hub.pins ?? []).filter((q) => q.kind === kind);
  if (!pins.length) return null;
  const [nx, nz] = centre(next);
  let best = pins[0]!, bd = Infinity;
  for (const q of pins) { const d = Math.hypot(wrapDx(nx - q.x), nz - q.z); if (d < bd) { bd = d; best = q; } }
  return best;
}

/** A route's cells as a polyline, its ends on the pins of the hubs it starts or ends at. */
export function routePath(cells: readonly number[], kind: 'highway' | 'rail', hubAt: (c: number) => Place | undefined): Pt[] {
  const pts: Pt[] = cells.map(centre);
  if (cells.length >= 2) {
    const a = hubAt(cells[0]!);
    const qa = a ? pinFor(a, kind, cells[1]!) : null;
    const b = hubAt(cells[cells.length - 1]!);
    const qb = b ? pinFor(b, kind, cells[cells.length - 2]!) : null;
    if (qb) pts.splice(pts.length - 1, 1, out(qb, CELL_M / 2), [qb.x, qb.z]);
    if (qa) pts.splice(0, 1, [qa.x, qa.z], out(qa, CELL_M / 2));
  }
  return unwrap(pts);
}

/** The box (m, from the hub's centre) a river's cells give way inside: the pad and the approach margin. */
function riverBox(hub: Place): { hw: number; hh: number } {
  return { hw: hub.hub!.padM.w / 2 + APPROACH_M, hh: hub.hub!.padM.h / 2 + APPROACH_M };
}

/** Nearest point to [p] on the segment [a]-[b] (cell units; x taken next to p). */
function nearestOn(a: Pt, b: Pt, p: Pt): Pt {
  const ax = p[0] + wrapDx(a[0] - p[0]), bx = ax + wrapDx(b[0] - a[0]);
  const dx = bx - ax, dz = b[1] - a[1], l2 = dx * dx + dz * dz || 1e-12;
  const t = Math.max(0, Math.min(1, ((p[0] - ax) * dx + (p[1] - a[1]) * dz) / l2));
  return [wrapX(ax + dx * t), a[1] + dz * t];
}

export interface Paths {
  /** Per river (in w.hydro.rivers' order), per road, per rail line: a polyline in atlas cell units. */
  rivers: Pt[][];
  roads: Pt[][];
  rail: Pt[][];
}

/** Every vector's path (see the top of the file). */
export function worldPaths(w: World): Paths {
  const hubCell = new Map<number, Place>();
  for (const h of w.hubs) hubCell.set(h.cell, h);
  const hubAt = (c: number): Place | undefined => hubCell.get(c);
  const roads = w.roads.map((r) => r.path ? unwrap(r.path.map(([x, z]) => [x, z] as Pt)) : routePath(r.cells, 'highway', (c) => (c === r.cells[0] && r.a.startsWith('h:') ? hubAt(c) : c === r.cells[r.cells.length - 1] && r.b?.startsWith('h:') ? hubAt(c) : undefined)));
  const rail = w.rail.lines.map((l) => routePath(l.cells, 'rail', hubAt));
  const rivers = w.hydro.rivers.map((r) => r.cells.map(centre));
  for (const hub of w.hubs) {
    const pins = (hub.pins ?? []).filter((q) => q.kind === 'river');
    const pin = pins.find((q) => q.name.startsWith('river in')), pout = pins.find((q) => q.name.startsWith('river out'));
    if (!pin || !pout) continue;
    const { hw, hh } = riverBox(hub);
    const inside = (p: Pt): boolean => { const d = near(hub, p[0], p[1]); return Math.abs(d.dx) <= hw && Math.abs(d.dz) <= hh; };
    const inner: Pt[] = [out(pin, APPROACH_M), [pin.x, pin.z], [pout.x, pout.z], out(pout, APPROACH_M)];
    // the trunk: the segments through the hub's own cell (the one entering the pad and the one leaving it, or one through)
    w.hydro.rivers.forEach((r, k) => {
      const pts = rivers[k]!;
      const flags = pts.map(inside);
      if (!flags.some(Boolean)) return;
      const first = flags.indexOf(true), last = flags.lastIndexOf(true);
      const through = r.cells.includes(hub.cell);
      const before = pts.slice(0, first), after = pts.slice(last + 1);
      if (through) {
        // the river itself: in by the inflow pin, out by the outflow pin (the parts outside kept as they were)
        // (a stretch that starts inside, below a confluence in the pad, keeps only the way out; one that ends inside only the way in)
        rivers[k] = [...before, ...(first === 0 && last === pts.length - 1 ? inner : first === 0 ? inner.slice(2) : last === pts.length - 1 ? inner.slice(0, 2) : inner), ...after];
      } else if (last === pts.length - 1 && before.length) {
        // a tributary ending inside: it meets the bent river at its nearest point outside the plan
        const e = before[before.length - 1]!, [a, b] = nearApproach(inner, e);
        rivers[k] = [...before, nearestOn(a!, b!, e)];
      } else if (first === 0 && after.length) {
        // a stretch starting inside (below a confluence there): it starts from the outflow's approach
        rivers[k] = [inner[3]!, ...after];
      } else rivers[k] = [...before, ...after];
    });
  }
  return { rivers: rivers.map(unwrap), roads, rail };
}

/** The approach legs of a bent river (inflow's, outflow's) as one polyline set for a tributary to join: the nearer leg. */
function nearApproach(inner: Pt[], p: Pt): Pt[] {
  const dIn = Math.hypot(wrapDx(p[0] - inner[0]![0]), p[1] - inner[0]![1]);
  const dOut = Math.hypot(wrapDx(p[0] - inner[3]![0]), p[1] - inner[3]![1]);
  return dIn <= dOut ? [inner[0]!, inner[1]!] : [inner[2]!, inner[3]!];
}
