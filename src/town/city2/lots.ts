import { rand } from '../../sim/rng';
import { FRONT, type LotKind } from '../kinds';
import { roadGround } from './ground';
import { buildRoads, JUNCTIONS, newTownGrid, type BuiltRoad, type Street } from './roads';
import {
  CITY2_SEED,
  CLIFF_SEGMENTS,
  coastDistance,
  coastNearest,
  DISTRICTS,
  DUNES,
  groundHeight,
  inside,
  LANDMARKS,
  MARSH,
  RAIL,
  riverAt,
} from './site';

// DOVE BEACH FRONTAGE LOTS & LANDMARK PLOTS (G023 / CITY2.md Phase 5a).
// Lays deterministic oriented building lots along the curved, sloped road centrelines of `roads.ts`
// and Seagrove's 1905 street grid (`newTownGrid`). Every lot fronts a road, stays clear of all
// carriageways, junctions, bridges/tunnels, Heron Marsh, the Long Strand beach & dunes, the Sallow
// River, the railway, steep slopes, landmark reservations, and every other lot.
// Never moves or modifies a single road — the dove outline stays untouched.

export type XZ = readonly [number, number];

export const MAX_BUILDABLE_GRADE = 0.30;
export const SEAGROVE_STREET_WIDTH = 8;
export const LOT_SEPARATION_M = 1.5;

export type DoveBeachDistrictId =
  | 'harbour'
  | 'oldtown'
  | 'mills'
  | 'newtown'
  | 'strand'
  | 'fellside'
  | 'crown'
  | 'ridge'
  | 'eastcove'
  | 'head'
  | 'marsh'
  | 'uplands';

export type LandmarkFootprintKind = 'building' | 'monument' | 'infrastructure' | 'kit';

export interface LandmarkPlot {
  id: string;
  name: string;
  districtId: DoveBeachDistrictId;
  at: XZ;
  from: number;
  to?: number;
  /** Footprint width (m) along heading and depth (m) across it. */
  w: number;
  d: number;
  heading: number;
  corners: [XZ, XZ, XZ, XZ];
  y: number;
  who: 'Sam' | 'Jeff' | 'Claude';
  task: string;
  footprintKind: LandmarkFootprintKind;
  /** True if regular building lots must keep outside this reservation polygon. */
  exclusive: boolean;
}

export interface City2Lot {
  id: string;
  roadId: string;
  roadName: string;
  /** Chainage along the fronting road/street (m). */
  roadS: number;
  /** +1 = road right-hand normal, -1 = left-hand normal. */
  side: 1 | -1;
  districtId: DoveBeachDistrictId;
  /** Building kind from the capital's `src/town/kinds.ts`. */
  kind: LotKind;
  /** Frontage width along road (m). */
  w: number;
  /** Lot depth perpendicular to road (m). */
  d: number;
  /** Front yard setback from road edge to building pad (m). */
  frontYard: number;
  /** Rear yard setback behind building pad (m). */
  backYard: number;
  /** Building pad width & depth inside the lot (m). */
  padW: number;
  padD: number;
  /** Lot center in Dove Beach metres. */
  center: XZ;
  /** Midpoint of the front property line facing the road. */
  frontPoint: XZ;
  /** Angle (rad) along the fronting road tangent. */
  heading: number;
  /** Angle (rad) pointing from the lot toward the fronting road. */
  facingAngle: number;
  /** Oriented 4-corner lot polygon: [frontLeft, frontRight, backRight, backLeft]. */
  corners: [XZ, XZ, XZ, XZ];
  /** Oriented 4-corner building pad polygon inside the lot. */
  padCorners: [XZ, XZ, XZ, XZ];
  /** Leveled pad elevation (m above sea level). */
  y: number;
  /** Natural ground elevation at lot center (m). */
  groundY: number;
  /** Maximum terrain grade across the lot footprint (rise / run). */
  grade: number;
  /** Year the fronting road was built (a building never predates its street, except early harbour). */
  roadBuiltYear: number;
}

export interface City2LotLayout {
  seed: string;
  roads: BuiltRoad[];
  streets: Street[];
  landmarkPlots: LandmarkPlot[];
  lots: City2Lot[];
}

// ---------------------------------------------------------------------------
// Geometry helpers (pure 2D, exact SAT polygon intersection)
// ---------------------------------------------------------------------------

function segDist2D(px: number, pz: number, a: XZ, b: XZ): number {
  const dx = b[0] - a[0];
  const dz = b[1] - a[1];
  const l2 = dx * dx + dz * dz;
  const t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((px - a[0]) * dx + (pz - a[1]) * dz) / l2));
  return Math.hypot(px - (a[0] + dx * t), pz - (a[1] + dz * t));
}

function polyLineDist(px: number, pz: number, pts: readonly XZ[]): number {
  let best = Infinity;
  for (let i = 0; i + 1 < pts.length; i++) {
    const d = segDist2D(px, pz, pts[i]!, pts[i + 1]!);
    if (d < best) best = d;
  }
  return best;
}

export function orientedRectCorners(
  cx: number,
  cz: number,
  tx: number,
  tz: number,
  ox: number,
  oz: number,
  w: number,
  d: number,
): [XZ, XZ, XZ, XZ] {
  const hw = w / 2;
  const hd = d / 2;
  // Front edge is at -hd along outward normal (ox, oz), back edge at +hd.
  return [
    [cx - tx * hw - ox * hd, cz - tz * hw - oz * hd],
    [cx + tx * hw - ox * hd, cz + tz * hw - oz * hd],
    [cx + tx * hw + ox * hd, cz + tz * hw + oz * hd],
    [cx - tx * hw + ox * hd, cz - tz * hw + oz * hd],
  ];
}

function polyBBox(pts: readonly XZ[], pad = 0): [number, number, number, number] {
  let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
  for (let i = 0; i < pts.length; i++) {
    const p = pts[i]!;
    if (p[0] < minX) minX = p[0];
    if (p[0] > maxX) maxX = p[0];
    if (p[1] < minZ) minZ = p[1];
    if (p[1] > maxZ) maxZ = p[1];
  }
  return [minX - pad, minZ - pad, maxX + pad, maxZ + pad];
}

/** Exact Separating Axis Theorem (SAT) overlap test for convex 2D polygons, with optional separation buffer (m). */
export function polygonsOverlap(a: readonly XZ[], b: readonly XZ[], pad = 0): boolean {
  const checkAxes = (poly: readonly XZ[]): boolean => {
    for (let i = 0; i < poly.length; i++) {
      const p0 = poly[i]!;
      const p1 = poly[(i + 1) % poly.length]!;
      const ex = p1[0] - p0[0];
      const ez = p1[1] - p0[1];
      const len = Math.hypot(ex, ez);
      if (len < 1e-9) continue;
      const ax = -ez / len;
      const az = ex / len;

      let minA = Infinity, maxA = -Infinity;
      for (let k = 0; k < a.length; k++) {
        const v = a[k]![0] * ax + a[k]![1] * az;
        if (v < minA) minA = v;
        if (v > maxA) maxA = v;
      }
      let minB = Infinity, maxB = -Infinity;
      for (let k = 0; k < b.length; k++) {
        const v = b[k]![0] * ax + b[k]![1] * az;
        if (v < minB) minB = v;
        if (v > maxB) maxB = v;
      }
      if (maxA + pad <= minB || maxB + pad <= minA) return false; // separated
    }
    return true;
  };
  return checkAxes(a) && checkAxes(b);
}

// ---------------------------------------------------------------------------
// Site constraints: marsh, beach/dunes, cliffs, river, rail, slope, district
// ---------------------------------------------------------------------------

const isCliffSeg = (seg: number): boolean =>
  CLIFF_SEGMENTS.some(([a, b]) => seg >= a && seg <= b);

export function isInMarsh(x: number, z: number): boolean {
  return inside(x, z, MARSH);
}

/** True on the Long Strand sandy beach and coastal dune band (seaward of the Esplanade). */
export function isOnBeachOrDunes(x: number, z: number): boolean {
  const cn = coastNearest(x, z);
  // Segments 0..11 trace the west beach and Long Strand up to the harbour mouth.
  if (cn.seg <= 11 && cn.d < 125) return true;
  // Also protect the dune ridge polyline directly.
  if (polyLineDist(x, z, DUNES) < 28) return true;
  return false;
}

export function distToRail(x: number, z: number): number {
  return polyLineDist(x, z, RAIL);
}

/** Ground slope (rise / run) estimated from natural terrain around (x, z). */
export function groundSlopeAt(x: number, z: number, span = 8): number {
  const gx = (groundHeight(x + span, z) - groundHeight(x - span, z)) / (2 * span);
  const gz = (groundHeight(x, z + span) - groundHeight(x, z - span)) / (2 * span);
  return Math.hypot(gx, gz);
}

/** True when a single point sits on dry, buildable land off the sea, beach, dunes, cliffs, marsh, river, and rail. */
export function isBuildablePoint(x: number, z: number): boolean {
  if (isInMarsh(x, z)) return false;
  const cn = coastNearest(x, z);
  if (cn.d <= 0) return false;
  if (isCliffSeg(cn.seg) && cn.d < 28) return false;
  if (isOnBeachOrDunes(x, z)) return false;
  // Keep harbour/eastcove shoreline back from the water edge.
  if (cn.d < 16) return false;
  // Keep off the Sallow River channel and banks.
  if (riverAt(x, z).d < 26) return false;
  // Keep clear of the railway line.
  if (distToRail(x, z) < 13) return false;
  if (groundHeight(x, z) < 1.2) return false;
  return true;
}

const DISTRICT_PRIORITY: DoveBeachDistrictId[] = [
  'harbour',
  'strand',
  'mills',
  'oldtown',
  'newtown',
  'crown',
  'ridge',
  'fellside',
  'eastcove',
  'uplands',
  'head',
  'marsh',
];

export function districtAt(x: number, z: number): DoveBeachDistrictId | null {
  for (let i = 0; i < DISTRICT_PRIORITY.length; i++) {
    const id = DISTRICT_PRIORITY[i]!;
    const d = DISTRICTS.find((q) => q.id === id);
    if (d && inside(x, z, d.poly)) return id;
  }
  return null;
}

function nearestDistrict(x: number, z: number): DoveBeachDistrictId {
  const exact = districtAt(x, z);
  if (exact) return exact;
  let bestId: DoveBeachDistrictId = 'oldtown';
  let bestD = Infinity;
  for (const d of DISTRICTS) {
    let cx = 0, cz = 0;
    for (const p of d.poly) { cx += p[0]; cz += p[1]; }
    cx /= d.poly.length;
    cz /= d.poly.length;
    const dist = Math.hypot(x - cx, z - cz);
    if (dist < bestD) {
      bestD = dist;
      bestId = d.id as DoveBeachDistrictId;
    }
  }
  return bestId;
}

// ---------------------------------------------------------------------------
// Spatial road-clearance index (covers all curved roads + Seagrove grid streets)
// ---------------------------------------------------------------------------

interface RoadSegEntry {
  x0: number;
  z0: number;
  x1: number;
  z1: number;
  halfW: number;
  roadId: string;
}

class RoadClearanceIndex {
  private readonly cell = 40;
  private readonly cells = new Map<string, number[]>();
  private readonly segs: RoadSegEntry[] = [];

  constructor(roads: readonly BuiltRoad[], streets: readonly Street[]) {
    for (const r of roads) {
      const ss = r.a.samples;
      const halfW = r.spec.width / 2;
      // Step by 4m segments so curved carriageways are accurately bounded.
      const step = 4;
      for (let i = 0; i < ss.length - 1; i += step) {
        const a = ss[i]!;
        const b = ss[Math.min(ss.length - 1, i + step)]!;
        this.addSeg(a.x, a.z, b.x, b.z, halfW, r.spec.id);
      }
    }
    for (const st of streets) {
      const len = Math.hypot(st.b[0] - st.a[0], st.b[1] - st.a[1]);
      const n = Math.max(1, Math.ceil(len / 30));
      for (let i = 0; i < n; i++) {
        const t0 = i / n, t1 = (i + 1) / n;
        this.addSeg(
          st.a[0] + (st.b[0] - st.a[0]) * t0,
          st.a[1] + (st.b[1] - st.a[1]) * t0,
          st.a[0] + (st.b[0] - st.a[0]) * t1,
          st.a[1] + (st.b[1] - st.a[1]) * t1,
          SEAGROVE_STREET_WIDTH / 2,
          st.id,
        );
      }
    }
  }

  private addSeg(x0: number, z0: number, x1: number, z1: number, halfW: number, roadId: string): void {
    const idx = this.segs.length;
    this.segs.push({ x0, z0, x1, z1, halfW, roadId });
    const pad = halfW + 4;
    const ix0 = Math.floor((Math.min(x0, x1) - pad) / this.cell);
    const ix1 = Math.floor((Math.max(x0, x1) + pad) / this.cell);
    const iz0 = Math.floor((Math.min(z0, z1) - pad) / this.cell);
    const iz1 = Math.floor((Math.max(z0, z1) + pad) / this.cell);
    for (let ix = ix0; ix <= ix1; ix++) {
      for (let iz = iz0; iz <= iz1; iz++) {
        const key = `${ix},${iz}`;
        let list = this.cells.get(key);
        if (!list) this.cells.set(key, (list = []));
        list.push(idx);
      }
    }
  }

  /** Minimum clearance from point (x, z) to any carriageway edge (< 0 means on the road!). */
  clearance(x: number, z: number): number {
    const cx = Math.floor(x / this.cell);
    const cz = Math.floor(z / this.cell);
    let best = Infinity;
    for (let dx = -1; dx <= 1; dx++) {
      for (let dz = -1; dz <= 1; dz++) {
        const list = this.cells.get(`${cx + dx},${cz + dz}`);
        if (!list) continue;
        for (let i = 0; i < list.length; i++) {
          const s = this.segs[list[i]!]!;
          const d = segDist2D(x, z, [s.x0, s.z0], [s.x1, s.z1]) - s.halfW;
          if (d < best) best = d;
        }
      }
    }
    return best;
  }
}

// ---------------------------------------------------------------------------
// Landmark plot reservations (CITY2.md landmarks by date)
// ---------------------------------------------------------------------------

interface LandmarkSpec {
  w: number;
  d: number;
  footprintKind: LandmarkFootprintKind;
  exclusive: boolean;
  districtOverride?: DoveBeachDistrictId;
}

const LANDMARK_SPECS: Record<string, LandmarkSpec> = {
  observatory: { w: 46, d: 40, footprintKind: 'building', exclusive: true, districtOverride: 'crown' },
  funicular: { w: 26, d: 34, footprintKind: 'infrastructure', exclusive: true, districtOverride: 'oldtown' },
  customhouse: { w: 38, d: 32, footprintKind: 'building', exclusive: true, districtOverride: 'harbour' },
  pavilion: { w: 34, d: 44, footprintKind: 'building', exclusive: false, districtOverride: 'strand' },
  fountain: { w: 28, d: 28, footprintKind: 'monument', exclusive: true, districtOverride: 'strand' },
  lighthouse: { w: 42, d: 38, footprintKind: 'building', exclusive: true, districtOverride: 'head' },
  fort: { w: 80, d: 70, footprintKind: 'building', exclusive: true, districtOverride: 'head' },
  railviaduct: { w: 24, d: 90, footprintKind: 'infrastructure', exclusive: false, districtOverride: 'mills' },
  roadviaduct: { w: 24, d: 90, footprintKind: 'infrastructure', exclusive: false, districtOverride: 'fellside' },
  tunnel: { w: 28, d: 28, footprintKind: 'infrastructure', exclusive: true, districtOverride: 'harbour' },
  belvedere: { w: 36, d: 26, footprintKind: 'monument', exclusive: true, districtOverride: 'ridge' },
  mast: { w: 32, d: 32, footprintKind: 'infrastructure', exclusive: true, districtOverride: 'ridge' },
  shrimpdock: { w: 56, d: 40, footprintKind: 'building', exclusive: true, districtOverride: 'eastcove' },
  station: { w: 54, d: 34, footprintKind: 'building', exclusive: true, districtOverride: 'harbour' },
  brewery: { w: 52, d: 38, footprintKind: 'building', exclusive: true, districtOverride: 'mills' },
  drawbridge: { w: 24, d: 36, footprintKind: 'infrastructure', exclusive: false, districtOverride: 'marsh' },
  creekdock: { w: 44, d: 30, footprintKind: 'building', exclusive: true, districtOverride: 'marsh' },
  dunes: { w: 20, d: 20, footprintKind: 'kit', exclusive: false, districtOverride: 'strand' },
  cottages: { w: 20, d: 20, footprintKind: 'kit', exclusive: false, districtOverride: 'newtown' },
};

function nearestRoadHeading(x: number, z: number, roads: readonly BuiltRoad[]): number {
  let best = Infinity;
  let heading = 0;
  for (const r of roads) {
    for (let i = 0; i < r.a.samples.length; i += 6) {
      const q = r.a.samples[i]!;
      const d = Math.hypot(q.x - x, q.z - z);
      if (d < best) {
        best = d;
        heading = q.h;
      }
    }
  }
  return heading;
}

export function reserveLandmarkPlots(roads: readonly BuiltRoad[] = buildRoads()): LandmarkPlot[] {
  const rg = roadGround(roads as BuiltRoad[]);
  return LANDMARKS.map((lm) => {
    const spec = LANDMARK_SPECS[lm.id] ?? { w: 32, d: 28, footprintKind: 'building' as const, exclusive: true };
    const heading = nearestRoadHeading(lm.at[0], lm.at[1], roads);
    const tx = Math.cos(heading), tz = Math.sin(heading);
    const ox = -tz, oz = tx;
    const corners = orientedRectCorners(lm.at[0], lm.at[1], tx, tz, ox, oz, spec.w, spec.d);
    const districtId = spec.districtOverride ?? nearestDistrict(lm.at[0], lm.at[1]);
    return {
      id: lm.id,
      name: lm.name,
      districtId,
      at: [lm.at[0], lm.at[1]],
      from: lm.from,
      ...(lm.to !== undefined ? { to: lm.to } : {}),
      w: spec.w,
      d: spec.d,
      heading,
      corners,
      y: Math.round(rg.height(lm.at[0], lm.at[1]) * 100) / 100,
      who: lm.who,
      task: lm.task,
      footprintKind: spec.footprintKind,
      exclusive: spec.exclusive,
    };
  });
}

// ---------------------------------------------------------------------------
// District lot sizing profiles (grounded in CITY2.md + src/town/kinds.ts)
// ---------------------------------------------------------------------------

interface DistrictLotProfile {
  kinds: { kind: LotKind; weight: number }[];
  wRange: [number, number];
  dRange: [number, number];
  verge: number;
  gap: number;
  frontYardRange: [number, number];
  backYardRange: [number, number];
  density: number; // probability of placing a lot when buildable
}

const DISTRICT_PROFILES: Record<DoveBeachDistrictId, DistrictLotProfile> = {
  harbour: {
    kinds: [{ kind: 'shop', weight: 0.65 }, { kind: 'small', weight: 0.25 }, { kind: 'apartment', weight: 0.10 }],
    wRange: [13, 18], dRange: [17, 22], verge: 2.5, gap: 2.0,
    frontYardRange: [0, 2], backYardRange: [2.5, 4.5], density: 0.92,
  },
  oldtown: {
    kinds: [{ kind: 'small', weight: 0.35 }, { kind: 'medium', weight: 0.30 }, { kind: 'shop', weight: 0.20 }, { kind: 'apartment', weight: 0.15 }],
    wRange: [12, 17], dRange: [16, 22], verge: 2.5, gap: 2.0,
    frontYardRange: [1.5, 3.5], backYardRange: [3, 5], density: 0.88,
  },
  mills: {
    kinds: [{ kind: 'apartment', weight: 0.45 }, { kind: 'shop', weight: 0.35 }, { kind: 'estate', weight: 0.20 }],
    wRange: [22, 30], dRange: [22, 27], verge: 3.0, gap: 4.0,
    frontYardRange: [2, 3.5], backYardRange: [4, 6], density: 0.82,
  },
  strand: {
    kinds: [{ kind: 'shop', weight: 0.50 }, { kind: 'medium', weight: 0.25 }, { kind: 'apartment', weight: 0.25 }],
    wRange: [15, 22], dRange: [20, 26], verge: 3.5, gap: 2.5,
    frontYardRange: [1.5, 4], backYardRange: [4, 6], density: 0.90,
  },
  newtown: {
    kinds: [{ kind: 'small', weight: 0.30 }, { kind: 'medium', weight: 0.45 }, { kind: 'house', weight: 0.25 }],
    wRange: [15, 21], dRange: [19, 25], verge: 2.5, gap: 2.5,
    frontYardRange: [4, 6], backYardRange: [5, 7.5], density: 0.84,
  },
  fellside: {
    kinds: [{ kind: 'medium', weight: 0.45 }, { kind: 'house', weight: 0.40 }, { kind: 'shop', weight: 0.15 }],
    wRange: [17, 23], dRange: [21, 27], verge: 3.0, gap: 3.5,
    frontYardRange: [4.5, 6.5], backYardRange: [5.5, 8], density: 0.72,
  },
  crown: {
    kinds: [{ kind: 'villa', weight: 0.65 }, { kind: 'estate', weight: 0.20 }, { kind: 'house', weight: 0.15 }],
    wRange: [22, 30], dRange: [24, 30], verge: 3.0, gap: 6.0,
    frontYardRange: [5, 7.5], backYardRange: [6, 9], density: 0.62,
  },
  ridge: {
    kinds: [{ kind: 'villa', weight: 0.55 }, { kind: 'estate', weight: 0.45 }],
    wRange: [26, 36], dRange: [26, 32], verge: 3.5, gap: 8.0,
    frontYardRange: [6, 8.5], backYardRange: [7, 10], density: 0.58,
  },
  eastcove: {
    kinds: [{ kind: 'shop', weight: 0.40 }, { kind: 'medium', weight: 0.35 }, { kind: 'house', weight: 0.25 }],
    wRange: [18, 25], dRange: [20, 26], verge: 3.0, gap: 3.5,
    frontYardRange: [2.5, 5], backYardRange: [4.5, 7], density: 0.80,
  },
  head: {
    kinds: [{ kind: 'small', weight: 0.35 }, { kind: 'medium', weight: 0.35 }, { kind: 'villa', weight: 0.30 }],
    wRange: [18, 24], dRange: [20, 25], verge: 3.0, gap: 14.0,
    frontYardRange: [4.5, 6.5], backYardRange: [5.5, 8], density: 0.24,
  },
  uplands: {
    kinds: [{ kind: 'house', weight: 0.55 }, { kind: 'medium', weight: 0.30 }, { kind: 'shop', weight: 0.15 }],
    wRange: [18, 24], dRange: [20, 25], verge: 3.0, gap: 3.0,
    frontYardRange: [4.5, 6.5], backYardRange: [5.5, 8], density: 0.96,
  },
  // Heron Marsh is protected salt marsh: zero regular building lots.
  marsh: {
    kinds: [{ kind: 'small', weight: 1 }],
    wRange: [14, 16], dRange: [16, 18], verge: 3.0, gap: 10,
    frontYardRange: [4, 5], backYardRange: [5, 6], density: 0,
  },
};

function pickKind(profile: DistrictLotProfile, r: number): LotKind {
  let acc = 0;
  for (const item of profile.kinds) {
    acc += item.weight;
    if (r <= acc) return item.kind;
  }
  return profile.kinds[profile.kinds.length - 1]!.kind;
}

function junctionClearanceOk(pts: readonly XZ[]): boolean {
  for (const [jid, j] of Object.entries(JUNCTIONS)) {
    const rad = jid === 'fountain' ? 38 : jid === 'strandEnd' ? 30 : 15;
    for (let i = 0; i < pts.length; i++) {
      if (Math.hypot(pts[i]![0] - j.at[0], pts[i]![1] - j.at[1]) < rad) return false;
    }
  }
  return true;
}

// Spatial hash for fast exact SAT lot overlap rejection
class LotGrid {
  private readonly cell = 45;
  private readonly cells = new Map<string, number[]>();
  readonly items: { corners: [XZ, XZ, XZ, XZ]; bbox: [number, number, number, number] }[] = [];

  overlaps(corners: [XZ, XZ, XZ, XZ], pad: number): boolean {
    const bb = polyBBox(corners, pad);
    const ix0 = Math.floor(bb[0] / this.cell);
    const ix1 = Math.floor(bb[2] / this.cell);
    const iz0 = Math.floor(bb[1] / this.cell);
    const iz1 = Math.floor(bb[3] / this.cell);
    const visited = new Set<number>();
    for (let ix = ix0; ix <= ix1; ix++) {
      for (let iz = iz0; iz <= iz1; iz++) {
        const list = this.cells.get(`${ix},${iz}`);
        if (!list) continue;
        for (let i = 0; i < list.length; i++) {
          const idx = list[i]!;
          if (visited.has(idx)) continue;
          visited.add(idx);
          const other = this.items[idx]!;
          if (
            bb[2] < other.bbox[0] || bb[0] > other.bbox[2] ||
            bb[3] < other.bbox[1] || bb[1] > other.bbox[3]
          ) {
            continue;
          }
          if (polygonsOverlap(corners, other.corners, pad)) return true;
        }
      }
    }
    return false;
  }

  add(corners: [XZ, XZ, XZ, XZ]): void {
    const idx = this.items.length;
    const bbox = polyBBox(corners, 0);
    this.items.push({ corners, bbox });
    const ix0 = Math.floor(bbox[0] / this.cell);
    const ix1 = Math.floor(bbox[2] / this.cell);
    const iz0 = Math.floor(bbox[1] / this.cell);
    const iz1 = Math.floor(bbox[3] / this.cell);
    for (let ix = ix0; ix <= ix1; ix++) {
      for (let iz = iz0; iz <= iz1; iz++) {
        const key = `${ix},${iz}`;
        let list = this.cells.get(key);
        if (!list) this.cells.set(key, (list = []));
        list.push(idx);
      }
    }
  }
}

interface FrontageLine {
  id: string;
  name: string;
  halfW: number;
  built: number;
  length: number;
  /** True when the road runs in Seagrove's 1905 street grid. */
  isSeagroveGrid: boolean;
  sampleAt(s: number): { x: number; z: number; h: number; inSpan: boolean };
}

function buildFrontageLines(roads: readonly BuiltRoad[], streets: readonly Street[]): FrontageLine[] {
  const lines: FrontageLine[] = [];
  // Order matters deterministically: historic harbour/old town/strand/mills first, then grid, then hills/outer.
  const order = [
    'harbour', 'steep', 'crownlane', 'neckhill', 'mill', 'esplanade',
    'fellside', 'crown', 'ridge', 'tunnel', 'head', 'pass',
  ];
  const byId = new Map(roads.map((r) => [r.spec.id, r]));

  for (const id of order) {
    const r = byId.get(id);
    if (!r) continue;
    const ss = r.a.samples;
    const ds = r.a.length / Math.max(1, ss.length - 1);
    lines.push({
      id: r.spec.id,
      name: r.spec.name,
      halfW: r.spec.width / 2,
      built: r.spec.built,
      length: r.a.length,
      isSeagroveGrid: false,
      sampleAt(s: number) {
        const fi = Math.max(0, Math.min(ss.length - 1, s / ds));
        const i0 = Math.floor(fi);
        const i1 = Math.min(ss.length - 1, i0 + 1);
        const t = fi - i0;
        const a = ss[i0]!, b = ss[i1]!;
        let dh = b.h - a.h;
        while (dh > Math.PI) dh -= 2 * Math.PI;
        while (dh < -Math.PI) dh += 2 * Math.PI;
        const inSpan = r.spans.some((sp) => s >= sp.from - 14 && s <= sp.to + 14);
        return {
          x: a.x + (b.x - a.x) * t,
          z: a.z + (b.z - a.z) * t,
          h: a.h + dh * t,
          inSpan,
        };
      },
    });
  }

  for (const st of streets) {
    const dx = st.b[0] - st.a[0];
    const dz = st.b[1] - st.a[1];
    const len = Math.hypot(dx, dz);
    if (len < 24) continue;
    const h = Math.atan2(dz, dx);
    lines.push({
      id: st.id,
      name: st.name,
      halfW: SEAGROVE_STREET_WIDTH / 2,
      built: 1905,
      length: len,
      isSeagroveGrid: true,
      sampleAt(s: number) {
        const t = Math.max(0, Math.min(1, s / len));
        return {
          x: st.a[0] + dx * t,
          z: st.a[1] + dz * t,
          h,
          inSpan: false,
        };
      },
    });
  }

  return lines;
}

/**
 * Generate all frontage lots and reserved landmark plots for Dove Beach, deterministically from `seed`.
 */
export function generateLots(
  seed = CITY2_SEED,
  roadsInput?: BuiltRoad[],
  streetsInput?: Street[],
  landmarksInput?: LandmarkPlot[],
): City2LotLayout {
  const roads = roadsInput ?? buildRoads();
  const ntPoly = DISTRICTS.find((d) => d.id === 'newtown')!.poly;
  const streets = streetsInput ?? newTownGrid(seed, ntPoly);
  const landmarkPlots = landmarksInput ?? reserveLandmarkPlots(roads);
  const rg = roadGround(roads);
  const roadIdx = new RoadClearanceIndex(roads, streets);
  const lotGrid = new LotGrid();
  const exclusiveLandmarks = landmarkPlots.filter((l) => l.exclusive);
  const lines = buildFrontageLines(roads, streets);

  const lots: City2Lot[] = [];
  let seq = 0;

  for (let li = 0; li < lines.length; li++) {
    const line = lines[li]!;
    for (const side of [1, -1] as const) {
      let s = 18;
      let stepIdx = 0;
      while (s < line.length - 18) {
        stepIdx++;
        const q = line.sampleAt(s);
        if (q.inSpan) {
          s += 14;
          continue;
        }

        const tx = Math.cos(q.h), tz = Math.sin(q.h);
        const nx = -tz, nz = tx;
        const ox = side * nx, oz = side * nz;

        // Probe slightly off the road to classify the district before choosing lot dimensions.
        const probeX = q.x + ox * (line.halfW + 14);
        const probeZ = q.z + oz * (line.halfW + 14);
        const distId: DoveBeachDistrictId | null = line.isSeagroveGrid
          ? 'newtown'
          : districtAt(probeX, probeZ);

        if (!distId || distId === 'marsh') {
          s += 14;
          continue;
        }

        const profile = DISTRICT_PROFILES[distId];
        if (profile.density <= 0) {
          s += 16;
          continue;
        }

        const rW = rand(seed, 'lot-w', line.id, side, stepIdx);
        const rD = rand(seed, 'lot-d', line.id, side, stepIdx);
        const rK = rand(seed, 'lot-k', line.id, side, stepIdx);
        const rFY = rand(seed, 'lot-fy', line.id, side, stepIdx);
        const rBY = rand(seed, 'lot-by', line.id, side, stepIdx);
        const rKeep = rand(seed, 'lot-keep', line.id, side, stepIdx);

        const w = Math.round((profile.wRange[0] + rW * (profile.wRange[1] - profile.wRange[0])) * 10) / 10;
        const d = Math.round((profile.dRange[0] + rD * (profile.dRange[1] - profile.dRange[0])) * 10) / 10;

        // Deterministic gaps (especially in scenic hills / Head parkland) so streets breathe naturally.
        if (rKeep > profile.density) {
          s += w * 0.65 + profile.gap;
          continue;
        }

        const kind = pickKind(profile, rK);
        const frontOffset = line.halfW + profile.verge;
        const frontX = q.x + ox * frontOffset;
        const frontZ = q.z + oz * frontOffset;
        const cx = frontX + ox * (d / 2);
        const cz = frontZ + oz * (d / 2);
        const corners = orientedRectCorners(cx, cz, tx, tz, ox, oz, w, d);

        // Dense sample points covering corners, edges, and center for rock-solid clearance checks.
        const checkPts: XZ[] = [
          [cx, cz],
          corners[0], corners[1], corners[2], corners[3],
          [(corners[0][0] + corners[1][0]) / 2, (corners[0][1] + corners[1][1]) / 2],
          [(corners[1][0] + corners[2][0]) / 2, (corners[1][1] + corners[2][1]) / 2],
          [(corners[2][0] + corners[3][0]) / 2, (corners[2][1] + corners[3][1]) / 2],
          [(corners[3][0] + corners[0][0]) / 2, (corners[3][1] + corners[0][1]) / 2],
        ];

        // 0. Fast reject: junction clearance & existing lot overlap before terrain/road queries.
        if (!junctionClearanceOk(checkPts)) { s += 12; continue; }
        if (lotGrid.overlaps(corners, LOT_SEPARATION_M)) { s += 8; continue; }

        // 1. All points must be on buildable ground (no sea, beach, dunes, cliffs, marsh, river, rail).
        let ok = true;
        for (let i = 0; i < checkPts.length; i++) {
          const pt = checkPts[i]!;
          if (!isBuildablePoint(pt[0], pt[1])) { ok = false; break; }
        }
        if (!ok) { s += 10; continue; }

        // 3. Must sit strictly off EVERY carriageway (curved roads + Seagrove grid + roadGround bench).
        for (let i = 0; i < checkPts.length; i++) {
          const pt = checkPts[i]!;
          if (roadIdx.clearance(pt[0], pt[1]) < 1.2) { ok = false; break; }
          const rHit = rg.road(pt[0], pt[1]);
          if (rHit && rHit.out < 0.4) { ok = false; break; }
        }
        if (!ok) { s += 10; continue; }

        // 4. Terrain grade across the lot must stay buildable (<= MAX_BUILDABLE_GRADE).
        const h0 = groundHeight(corners[0][0], corners[0][1]);
        const h1 = groundHeight(corners[1][0], corners[1][1]);
        const h2 = groundHeight(corners[2][0], corners[2][1]);
        const h3 = groundHeight(corners[3][0], corners[3][1]);
        const hC = groundHeight(cx, cz);
        const diagLen = Math.hypot(w, d);
        const maxLotGrade = Math.max(
          Math.abs(h1 - h0) / w,
          Math.abs(h2 - h3) / w,
          Math.abs(h3 - h0) / d,
          Math.abs(h2 - h1) / d,
          Math.abs(h2 - h0) / diagLen,
          Math.abs(h3 - h1) / diagLen,
          groundSlopeAt(cx, cz, 6),
        );
        if (maxLotGrade > MAX_BUILDABLE_GRADE) {
          s += 10;
          continue;
        }

        // 5. Must not overlap any exclusive landmark reservation.
        for (let i = 0; i < exclusiveLandmarks.length; i++) {
          if (polygonsOverlap(corners, exclusiveLandmarks[i]!.corners, 2.0)) {
            ok = false;
            break;
          }
        }
        if (!ok) { s += 12; continue; }

        // Compute front & back yards and inner leveled building pad.
        const frontYard =
          FRONT[kind] === 0
            ? Math.round(rFY * Math.min(1.5, profile.frontYardRange[1]) * 10) / 10
            : Math.round((profile.frontYardRange[0] + rFY * (profile.frontYardRange[1] - profile.frontYardRange[0])) * 10) / 10;
        const rawBackYard =
          Math.round((profile.backYardRange[0] + rBY * (profile.backYardRange[1] - profile.backYardRange[0])) * 10) / 10;
        const sideMargin = kind === 'shop' || kind === 'apartment' ? 1.2 : 2.0;
        const padW = Math.max(6, Math.round((w - 2 * sideMargin) * 10) / 10);
        const padD = Math.max(7, Math.round((d - frontYard - rawBackYard) * 10) / 10);
        const backYard = Math.max(0, Math.round((d - frontYard - padD) * 10) / 10);
        const padCenterOffset = frontYard + padD / 2;
        const padCx = frontX + ox * padCenterOffset;
        const padCz = frontZ + oz * padCenterOffset;
        const padCorners = orientedRectCorners(padCx, padCz, tx, tz, ox, oz, padW, padD);
        const padY = Math.round(rg.height(padCx, padCz) * 100) / 100;

        lotGrid.add(corners);
        seq++;
        lots.push({
          id: `lot:${distId}:${seq}`,
          roadId: line.id,
          roadName: line.name,
          roadS: Math.round(s * 10) / 10,
          side,
          districtId: distId,
          kind,
          w,
          d,
          frontYard,
          backYard,
          padW,
          padD,
          center: [Math.round(cx * 100) / 100, Math.round(cz * 100) / 100],
          frontPoint: [Math.round(frontX * 100) / 100, Math.round(frontZ * 100) / 100],
          heading: Math.round(q.h * 10000) / 10000,
          facingAngle: Math.round(Math.atan2(-oz, -ox) * 10000) / 10000,
          corners,
          padCorners,
          y: padY,
          groundY: Math.round(hC * 100) / 100,
          grade: Math.round(maxLotGrade * 1000) / 1000,
          roadBuiltYear: line.built,
        });

        s += w + profile.gap;
      }
    }
  }

  return { seed, roads, streets, landmarkPlots, lots };
}
