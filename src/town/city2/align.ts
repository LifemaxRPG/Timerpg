// ROAD ALIGNMENT for the second hand-made city (kotlin-street/docs/CITY2.md): a road is authored the way a highway
// engineer lays one out, not drawn freehand. Its plan is a chain of vertices:
//
//  - `p`  a point the centre line passes through (the road's ends, a junction it must meet exactly);
//  - `pi` a corner: the tangents before and after meet at the point, and the corner is filleted by a circular arc of
//         radius `r` (the classic point-of-intersection layout: a sweeper is a pi with a big r);
//  - `c`  a circle the road wraps round, centre and radius given, turning L or R (a hairpin: the legs before and after are
//         tangent to it, so a stack of switchbacks is a stack of circles).
//
// Every vertex is turned into a circle (a point is a circle of radius 0), and consecutive circles are joined by their
// common tangent on the side the road turns (a belt round pulleys). So the line is exactly tangent lines and arcs, its
// curvature known everywhere, and its ends are where they were authored.
//
// The PROFILE: each vertex carries an elevation `y` (a point at itself, a pi at its arc's middle, a hairpin at both ends
// of its arc, so a switchback's turn is level and its legs take the climb), interpolated along the road and smoothed by
// a moving average of `lv` metres (a parabolic vertical curve over every change of grade).
//
// The CROSSFALL: curves are banked (superelevation) for the road's design speed, up to its `emax`, run in over 40 m.
//
// Units: metres, x east, z south (north is -z), y up; headings in radians from +x towards +z.

export type XZ = readonly [number, number];

export type Vertex =
  | { p: XZ; y: number }
  | { pi: XZ; r: number; y: number }
  | { c: XZ; r: number; turn: 'L' | 'R'; y: number };

/** One sampled point of a road's centre line (every `STEP` metres). */
export interface Sample {
  s: number;
  x: number;
  z: number;
  y: number;
  /** Heading (radians, from +x towards +z). */
  h: number;
  /** Signed curvature (1/m; positive turns right as seen on a north-up map). */
  k: number;
  /** The crossfall: dy per metre to the road's right (negative in a right-hand curve: the outside is higher). */
  bank: number;
}

/** A piece of the plan: a tangent line or an arc. */
export interface Piece {
  kind: 'line' | 'arc';
  s0: number;
  len: number;
  /** For an arc: centre, signed radius (+ right), the vertex index it came from. */
  cx?: number;
  cz?: number;
  rho?: number;
  v?: number;
}

export interface Alignment {
  pieces: Piece[];
  samples: Sample[];
  length: number;
}

export const STEP = 1;

const TAU = Math.PI * 2;
const mod = (a: number, m: number): number => ((a % m) + m) % m;

interface Circle { cx: number; cz: number; rho: number }

function anchor(v: Vertex): XZ {
  return 'p' in v ? v.p : 'pi' in v ? v.pi : v.c;
}

/** The vertices as circles (signed radius: + a right turn on a north-up map, which is + heading in x/z). */
function circles(vs: readonly Vertex[]): Circle[] {
  return vs.map((v, i) => {
    if ('p' in v) return { cx: v.p[0], cz: v.p[1], rho: 0 };
    if ('c' in v) return { cx: v.c[0], cz: v.c[1], rho: v.turn === 'R' ? v.r : -v.r };
    const a = anchor(vs[i - 1]!), b = anchor(vs[i + 1]!);
    const ax = a[0] - v.pi[0], az = a[1] - v.pi[1], bx = b[0] - v.pi[0], bz = b[1] - v.pi[1];
    const la = Math.hypot(ax, az), lb = Math.hypot(bx, bz);
    const ux = ax / la, uz = az / la, wx = bx / lb, wz = bz / lb;
    const between = Math.acos(Math.max(-1, Math.min(1, ux * wx + uz * wz)));
    let mx = ux + wx, mz = uz + wz;
    const ml = Math.hypot(mx, mz);
    if (ml < 1e-9) return { cx: v.pi[0], cz: v.pi[1], rho: 0 };
    mx /= ml; mz /= ml;
    const d = v.r / Math.sin(between / 2);
    // incoming direction -u, outgoing w: their cross product's sign is the turn's
    const cross = -ux * wz + uz * wx;
    return { cx: v.pi[0] + mx * d, cz: v.pi[1] + mz * d, rho: cross > 0 ? v.r : -v.r };
  });
}

/** The heading of the common tangent from circle a to circle b (each wrapped on its own side). */
function tangent(a: Circle, b: Circle): number {
  const dx = b.cx - a.cx, dz = b.cz - a.cz;
  const dd = Math.hypot(dx, dz);
  const dr = b.rho - a.rho;
  if (dd <= Math.abs(dr) + 1e-6) throw new Error(`circles too close for a tangent (${dd.toFixed(1)} m apart, radii ${a.rho}, ${b.rho})`);
  const l = Math.sqrt(dd * dd - dr * dr);
  return Math.atan2(dz, dx) + Math.atan2(-dr, l);
}

const onCircle = (c: Circle, h: number): [number, number] => [c.cx + c.rho * Math.sin(h), c.cz - c.rho * Math.cos(h)];

export interface ProfileOpts {
  /** Vertical curve length (m): the moving average's window. */
  lv: number;
  /** Design speed (m/s) for the banking, and the most bank (a share: 0.06 is 6%). */
  vDesign: number;
  emax: number;
}

/** Lay out a road: its pieces, and its centre line sampled every [STEP] m with height, heading, curvature and bank. */
export function align(vs: readonly Vertex[], o: ProfileOpts): Alignment {
  if (vs.length < 2) throw new Error('a road needs two vertices');
  if (!('p' in vs[0]!) || !('p' in vs[vs.length - 1]!)) throw new Error('a road starts and ends at a point');
  const cs = circles(vs);
  const th: number[] = [];
  for (let i = 0; i + 1 < cs.length; i++) th.push(tangent(cs[i]!, cs[i + 1]!));
  // the pieces: a tangent from each circle to the next, an arc on each inner circle
  const pieces: Piece[] = [];
  /** Control stations for the profile: [station, elevation]. */
  const ctrl: [number, number][] = [[0, vs[0]!.y]];
  let s = 0;
  for (let i = 0; i + 1 < cs.length; i++) {
    const a = cs[i]!, b = cs[i + 1]!;
    if (i > 0 && a.rho !== 0) {
      const hin = th[i - 1]!, hout = th[i]!;
      const sweep = a.rho > 0 ? mod(hout - hin, TAU) : mod(hin - hout, TAU);
      const len = Math.abs(a.rho) * sweep;
      const v = vs[i]!;
      if ('c' in v) { ctrl.push([s, v.y]); ctrl.push([s + len, v.y]); } else ctrl.push([s + len / 2, v.y]);
      pieces.push({ kind: 'arc', s0: s, len, cx: a.cx, cz: a.cz, rho: a.rho, v: i });
      s += len;
    } else if (i > 0) ctrl.push([s, vs[i]!.y]);
    const p0 = onCircle(a, th[i]!), p1 = onCircle(b, th[i]!);
    const len = Math.hypot(p1[0] - p0[0], p1[1] - p0[1]);
    pieces.push({ kind: 'line', s0: s, len });
    s += len;
  }
  ctrl.push([s, vs[vs.length - 1]!.y]);
  const length = s;

  // sample the plan
  const n = Math.max(2, Math.ceil(length / STEP) + 1);
  const samples: Sample[] = [];
  let pi = 0;
  // each line piece's start point and heading
  const lineAt: { x: number; z: number; h: number }[] = [];
  {
    let li = 0;
    for (let i = 0; i + 1 < cs.length; i++) {
      const p0 = onCircle(cs[i]!, th[i]!);
      lineAt[li++] = { x: p0[0], z: p0[1], h: th[i]! };
    }
  }
  const lineIndex = new Map<Piece, number>();
  { let li = 0; for (const p of pieces) if (p.kind === 'line') lineIndex.set(p, li++); }
  const arcIn = new Map<Piece, number>();
  for (const p of pieces) if (p.kind === 'arc') arcIn.set(p, th[p.v! - 1]!);
  for (let j = 0; j < n; j++) {
    const sj = Math.min(length, (j * length) / (n - 1));
    while (pi + 1 < pieces.length && sj > pieces[pi]!.s0 + pieces[pi]!.len) pi++;
    const p = pieces[pi]!;
    const t = sj - p.s0;
    if (p.kind === 'line') {
      const l = lineAt[lineIndex.get(p)!]!;
      samples.push({ s: sj, x: l.x + Math.cos(l.h) * t, z: l.z + Math.sin(l.h) * t, y: 0, h: l.h, k: 0, bank: 0 });
    } else {
      const sign = Math.sign(p.rho!);
      const h = arcIn.get(p)! + (sign * t) / Math.abs(p.rho!);
      const c = { cx: p.cx!, cz: p.cz!, rho: p.rho! };
      const [x, z] = onCircle(c, h);
      samples.push({ s: sj, x, z, y: 0, h, k: 1 / p.rho!, bank: 0 });
    }
  }
  // the profile: linear between the control stations, then the vertical curves
  const raw = samples.map((q) => interp(ctrl, q.s));
  const ys = boxSmooth(raw, Math.max(1, Math.round(o.lv / (length / (n - 1)))));
  // the banking: half the lateral demand at the design speed taken by the bank, up to emax, eased over 40 m
  const e = samples.map((q) => -Math.sign(q.k) * Math.min(o.emax, (0.5 * o.vDesign * o.vDesign * Math.abs(q.k)) / 9.81));
  const es = boxSmooth(e, Math.max(1, Math.round(40 / (length / (n - 1)))));
  samples.forEach((q, j) => { q.y = ys[j]!; q.bank = es[j]!; });
  return { pieces, samples, length };
}

function interp(ctrl: readonly (readonly [number, number])[], s: number): number {
  if (s <= ctrl[0]![0]) return ctrl[0]![1];
  for (let i = 1; i < ctrl.length; i++) {
    const [s1, y1] = ctrl[i]!, [s0, y0] = ctrl[i - 1]!;
    if (s <= s1) return s1 - s0 < 1e-9 ? y1 : y0 + ((y1 - y0) * (s - s0)) / (s1 - s0);
  }
  return ctrl[ctrl.length - 1]![1];
}

/** A centred moving average of [w] samples; the ends are held (the window shrinks symmetrically), so they stay exact. */
export function boxSmooth(a: readonly number[], w: number): number[] {
  const half = Math.floor(w / 2);
  const pre = [0];
  for (const v of a) pre.push(pre[pre.length - 1]! + v);
  return a.map((_, i) => {
    const h = Math.min(half, i, a.length - 1 - i);
    return (pre[i + h + 1]! - pre[i - h]!) / (2 * h + 1);
  });
}

// ------------------------------------------------------------------ measures

/** The race Torrel's lateral grip for the corner speed estimates (CARS.md: 11.6 m/s^2 at 90 km/h, 13.2 at 150). */
export function torrelGrip(v: number): number {
  return 11.0 + 2.2 * Math.min(1, Math.max(0, (v - 20) / 30));
}
export const TORREL_TOP = 273 / 3.6;

/** The speed (m/s) a race Torrel can hold round a radius [r] with bank [e] (grip plus the bank's share of gravity). */
export function cornerSpeed(r: number, e = 0): number {
  let v = 20;
  for (let i = 0; i < 20; i++) v = Math.sqrt((torrelGrip(v) + 9.81 * Math.abs(e)) * r);
  return Math.min(TORREL_TOP, v);
}

export interface Corner { v: number; radius: number; sweepDeg: number; len: number; s0: number; vKmh: number; bank: number }

/** Every arc of the plan, with the speed a race Torrel takes it at. */
export function corners(a: Alignment): Corner[] {
  return a.pieces.filter((p) => p.kind === 'arc').map((p) => {
    const mid = a.samples[Math.min(a.samples.length - 1, Math.round((p.s0 + p.len / 2) / (a.length / (a.samples.length - 1))))]!;
    const r = Math.abs(p.rho!);
    return { v: p.v!, radius: r, sweepDeg: (p.len / r) * (180 / Math.PI), len: p.len, s0: p.s0, vKmh: cornerSpeed(r, mid.bank) * 3.6, bank: Math.abs(mid.bank) };
  });
}

export interface ProfileStats { maxGrade: number; minCrestR: number; minSagR: number; climb: number }

/** The steepest grade, the tightest crest and sag (vertical radius, m; Infinity if none), over [win] m. */
export function profileStats(a: Alignment, win = 10): ProfileStats {
  const ss = a.samples;
  const ds = a.length / (ss.length - 1);
  const k = Math.max(1, Math.round(win / ds));
  let maxGrade = 0, minCrestR = Infinity, minSagR = Infinity, climb = 0;
  for (let i = 0; i + k < ss.length; i += 1) {
    const g = (ss[i + k]!.y - ss[i]!.y) / (k * ds);
    maxGrade = Math.max(maxGrade, Math.abs(g));
  }
  for (let i = 1; i < ss.length; i++) climb += Math.max(0, ss[i]!.y - ss[i - 1]!.y);
  for (let i = k; i + k < ss.length; i++) {
    const y2 = (ss[i + k]!.y - 2 * ss[i]!.y + ss[i - k]!.y) / ((k * ds) ** 2);
    if (Math.abs(y2) < 1e-7) continue;
    const r = 1 / Math.abs(y2);
    if (y2 < 0) minCrestR = Math.min(minCrestR, r); else minSagR = Math.min(minSagR, r);
  }
  return { maxGrade, minCrestR, minSagR, climb };
}

/** Share of the road's length that is curved (radius under 1,500 m), for the straight/curvy mix check (WORLD_GEN.md 5). */
export function curvedShare(a: Alignment): number {
  let c = 0;
  for (const p of a.pieces) if (p.kind === 'arc' && Math.abs(p.rho!) < 1500) c += p.len;
  return c / a.length;
}
