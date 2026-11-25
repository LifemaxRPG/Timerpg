import type { BuiltRoad } from './roads';
import { groundHeight } from './site';

// THE GROUND WITH ITS ROADS (kotlin-street/docs/CITY2.md): the natural ground (site.ts) with every road benched into it:
// the carriageway at the road's own height and crossfall, a 1.5 m shoulder, then cut and fill slopes at 1:1.5 back to the
// land (the renderer's embankments and cuttings). Over a bridge, through a tunnel or under the Bayway the land is left as
// it is. Where two roads are near, the nearer one shapes the ground.

const CELL = 60;
const REACH = 60;

export interface RoadGround {
  /** The ground's height with the roads cut and filled into it. */
  height(x: number, z: number): number;
  /** The nearest road surface within reach: its height there and how far outside its carriageway (0 on it). */
  road(x: number, z: number): { y: number; out: number; road: number } | null;
}

export function roadGround(roads: BuiltRoad[], ground: (x: number, z: number) => number = groundHeight): RoadGround {
  const cells = new Map<string, number[]>();
  const pts: { x: number; z: number; y: number; h: number; bank: number; half: number; road: number }[] = [];
  roads.forEach((r, ri) => {
    const half = r.spec.width / 2 + 1.5;
    r.a.samples.forEach((q, i) => {
      if (i % 2 !== 0) return;
      if (r.spans.some((sp) => q.s >= sp.from && q.s <= sp.to)) return;
      const k = `${Math.floor(q.x / CELL)},${Math.floor(q.z / CELL)}`;
      let c = cells.get(k);
      if (!c) cells.set(k, (c = []));
      c.push(pts.length);
      pts.push({ x: q.x, z: q.z, y: q.y, h: q.h, bank: q.bank, half, road: ri });
    });
  });
  const road = (x: number, z: number): { y: number; out: number; road: number } | null => {
    const cx = Math.floor(x / CELL), cz = Math.floor(z / CELL);
    let best = REACH * REACH, bi = -1;
    for (let j = -1; j <= 1; j++) for (let i = -1; i <= 1; i++) {
      const c = cells.get(`${cx + i},${cz + j}`);
      if (!c) continue;
      for (const k of c) {
        const p = pts[k]!;
        const d = (p.x - x) ** 2 + (p.z - z) ** 2;
        if (d < best) { best = d; bi = k; }
      }
    }
    if (bi < 0) return null;
    const p = pts[bi]!;
    // the offset to the road's right (its normal), and along it (to place the foot of the perpendicular)
    const o = (x - p.x) * -Math.sin(p.h) + (z - p.z) * Math.cos(p.h);
    const oc = Math.max(-p.half, Math.min(p.half, o));
    return { y: p.y + p.bank * oc, out: Math.max(0, Math.abs(o) - p.half), road: p.road };
  };
  return {
    road,
    height(x, z) {
      const g = ground(x, z);
      const r = road(x, z);
      if (!r) return g;
      if (r.out <= 0) return r.y;
      const reach = Math.min(REACH, Math.max(3, Math.abs(g - r.y) * 1.5));
      return r.y + (g - r.y) * Math.min(1, r.out / reach);
    },
  };
}
