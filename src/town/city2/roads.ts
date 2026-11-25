import { rand } from '../../sim/rng';
import { align, type Alignment, type Vertex, type XZ } from './align';
import { CITY2_SEED } from './site';

// THE SECOND CITY'S ROADS (kotlin-street/docs/CITY2.md): hand-authored alignments (align.ts) for every road that
// matters, and the New Town's grid generated from the seed. Every road is a real road of a real city first (a seafront
// promenade, the old turnpike, a carriage road up the hill, a Depression-era scenic drive, an old town high street, the
// quay); that they link into one loop with every kind of corner is the secret.

export type RoadClass = 'boulevard' | 'arterial' | 'expressway' | 'scenic' | 'hill' | 'street' | 'lane' | 'quay' | 'ramp';

export interface Era { from: number; to?: number; surface: 'dirt' | 'gravel' | 'macadam' | 'cobbles' | 'asphalt' | 'concrete'; lanes: number; note?: string }

/** A structure along a road: where it is (the road's nearest point to `near`) and how far it runs each way (m). */
export interface Span { near: XZ; half: number; kind: 'bridge' | 'viaduct' | 'tunnel' | 'elevated' }

export interface RoadSpec {
  id: string;
  name: string;
  cls: RoadClass;
  /** Carriageway width (m, kerb to kerb). */
  width: number;
  /** Design speed (km/h) for the banking, and the most bank. */
  design: number;
  emax: number;
  /** Vertical curve length (m). */
  lv: number;
  /** Most grade allowed (a share). */
  maxGrade: number;
  built: number;
  /** Pulled down (the Bayway). */
  gone?: number;
  eras: Era[];
  /** Structures along it, by station (m). */
  spans?: Span[];
  verts: Vertex[];
  /** The junctions at its ends. */
  ends: [string, string];
  /** Part of the loop the city's arterials happen to make. */
  loop?: number;
  note: string;
}

const P = (x: number, z: number, y: number): Vertex => ({ p: [x, z], y });
const I = (x: number, z: number, r: number, y: number): Vertex => ({ pi: [x, z], r, y });
const C = (x: number, z: number, r: number, turn: 'L' | 'R', y: number): Vertex => ({ c: [x, z], r, turn, y });

/** The junctions (named places where roads meet). */
export const JUNCTIONS: Record<string, { at: XZ; kind: 'roundabout' | 'junction' | 'interchange' | 'end'; r?: number; name: string }> = {
  fountain: { at: [-720, 210], kind: 'roundabout', r: 30, name: 'Fountain Circus' },
  strandEnd: { at: [-2693, 1963], kind: 'roundabout', r: 24, name: 'Strand End' },
  crownFoot: { at: [-649, -1028], kind: 'junction', name: 'Crown Foot' },
  crownTop: { at: [-300, -1600], kind: 'junction', name: 'Crown Top' },
  neck: { at: [1264, -327], kind: 'junction', name: 'Neck Cross' },
  quayEast: { at: [250, 215], kind: 'junction', name: 'Quay East' },
  headQuay: { at: [480, 190], kind: 'junction', name: 'Head Quay' },
  tunnelWest: { at: [560, 150], kind: 'junction', name: 'Tunnel West' },
  eastcove: { at: [2700, 300], kind: 'end', name: 'Eastcove (container port)' },
  knot: { at: [-893, 310], kind: 'interchange', name: 'The Knot' },
  coastWest: { at: [-3700, 2420], kind: 'end', name: 'Coast Highway west (to the coast towns)' },
  passNorth: { at: [-1560, -3300], kind: 'end', name: 'Pass Road north (the pass, 11 km, and the interior)' },
  steepFoot: { at: [0, 200], kind: 'junction', name: 'Custom House Square' },
  steepTop: { at: [-80, -720], kind: 'junction', name: 'Chapel Corner' },
};

const asphaltFrom = (y: number, lanes: number, note?: string): Era => ({ from: y, surface: 'asphalt', lanes, note });

export const ROADS: RoadSpec[] = [
  {
    id: 'esplanade', name: 'The Esplanade', cls: 'boulevard', width: 20, design: 90, emax: 0.04, lv: 120, maxGrade: 0.04,
    built: 1905, loop: 1, ends: ['fountain', 'strandEnd'],
    eras: [{ from: 1905, surface: 'gravel', lanes: 2, note: 'a carriage drive behind the promenade' }, { from: 1924, surface: 'macadam', lanes: 2, note: 'trams down the middle 1912-1954' }, asphaltFrom(1958, 4, 'dual carriageway, palms in the median'), { from: 2034, surface: 'asphalt', lanes: 2, note: 'two lanes and the tram back' }],
    verts: [P(-720, 210, 5), I(-1049, 400, 700, 5), I(-1308, 731, 450, 5), I(-1661, 919, 300, 6), I(-1873, 1234, 500, 5), I(-2222, 1469, 650, 5), I(-2433, 1760, 900, 5), P(-2693, 1963, 5)],
    note: 'the seafront: 2.7 km following the scalloped beach, sweepers of R 450-900 linked left-right, and one committed kink round the pier head (Pavilion Bend, R 300)',
  },
  {
    id: 'fellside', name: 'Fellside Road', cls: 'arterial', width: 12, design: 100, emax: 0.07, lv: 160, maxGrade: 0.06,
    built: 1832, loop: 2, ends: ['strandEnd', 'crownFoot'],
    eras: [{ from: 1832, surface: 'dirt', lanes: 2, note: 'the old turnpike to the Cleft' }, { from: 1910, surface: 'macadam', lanes: 2 }, asphaltFrom(1930, 2), asphaltFrom(1962, 4, 'rebuilt with the Fellside Viaduct')],
    spans: [{ near: [-990, -965], half: 210, kind: 'viaduct' }],
    verts: [P(-2693, 1963, 5), I(-2606, 1552, 400, 14), I(-2685, 1180, 180, 23), I(-2568, 861, 220, 32), I(-2613, 544, 160, 40), I(-2444, 226, 400, 48), I(-2132, -55, 300, 55), I(-1974, -378, 200, 60), I(-1618, -601, 350, 65), I(-1411, -896, 280, 69), P(-649, -1028, 75)],
    note: 'up the Fells\' foot in a chain of rising esses (R 160-400, the Fell Esses), then straight across the Cleft on the viaduct, 45 m over the river',
  },
  {
    id: 'crown', name: 'Crown Road', cls: 'hill', width: 7, design: 50, emax: 0.08, lv: 30, maxGrade: 0.09,
    built: 1894, loop: 3, ends: ['crownFoot', 'crownTop'],
    eras: [{ from: 1894, surface: 'gravel', lanes: 2, note: 'the carriage road to the Observatory' }, { from: 1921, surface: 'cobbles', lanes: 2, note: 'setts on the hairpins' }, asphaltFrom(1952, 2)],
    verts: [P(-649, -1028, 75), C(-610, -1420, 18, 'R', 107), C(-545, -1085, 16, 'L', 134), C(-480, -1430, 18, 'R', 163), C(-415, -1100, 16, 'L', 189), I(-395, -1600, 35, 222), P(-300, -1600, 225)],
    note: 'four stacked hairpins (R 16-18) on the Crown\'s west face, legs of 330-400 m at 8%: 150 m of climb in 2.1 km',
  },
  {
    id: 'ridge', name: 'Ridge Drive', cls: 'scenic', width: 8, design: 90, emax: 0.07, lv: 140, maxGrade: 0.07,
    built: 1936, loop: 4, ends: ['crownTop', 'neck'],
    eras: [{ from: 1936, surface: 'asphalt', lanes: 2, note: 'a Depression public work: stone walls and overlooks' }, asphaltFrom(1990, 2, 'Ridge Park; closed to cars at night 2045 on')],
    verts: [P(-300, -1600, 225), I(114, -1527, 200, 214), I(505, -1610, 150, 208), I(913, -1420, 180, 186), I(1142, -1092, 250, 170), I(1445, -917, 120, 146), I(1274, -447, 200, 116), P(1264, -327, 108)],
    note: 'along the ridge crest in flowing esses (R 120-250) and gentle crests, down to the Belvedere\'s long bend over the bay',
  },
  {
    id: 'neckhill', name: 'Neck Hill', cls: 'street', width: 9, design: 50, emax: 0.03, lv: 40, maxGrade: 0.12,
    built: 1802, loop: 5, ends: ['neck', 'quayEast'],
    eras: [{ from: 1802, surface: 'cobbles', lanes: 2, note: 'the way up to the fort' }, asphaltFrom(1938, 2), asphaltFrom(1972, 3, 'the container trucks to Eastcove, until the tunnel')],
    verts: [P(1264, -327, 108), I(821, -249, 110, 92), I(656, 37, 90, 55), I(366, 115, 60, 22), P(250, 215, 6)],
    note: 'the old town\'s steep high street, 100 m down in 1.2 km, 11% at its steepest, two linked lefts and a right',
  },
  {
    id: 'harbour', name: 'Harbour Road', cls: 'quay', width: 12, design: 50, emax: 0.02, lv: 20, maxGrade: 0.08,
    built: 1780, loop: 6, ends: ['quayEast', 'fountain'],
    eras: [{ from: 1780, surface: 'cobbles', lanes: 2, note: 'the quay; tram and dock rails in the setts' }, asphaltFrom(1955, 4)],
    spans: [{ near: [-380, 238], half: 30, kind: 'bridge' }],
    verts: [P(250, 215, 6), I(60, 190, 40, 5), I(-120, 245, 35, 5), I(-320, 240, 600, 5), I(-380, 238, 800, 9.5), I(-440, 236, 600, 5), P(-720, 210, 5)],
    note: 'the quay: two square corners round the Custom House and the fish market, then the humpbacked Sallow Bridge (1840)',
  },
  {
    id: 'head', name: 'Head Road', cls: 'scenic', width: 7, design: 70, emax: 0.07, lv: 90, maxGrade: 0.09,
    built: 1911, ends: ['neck', 'headQuay'],
    eras: [{ from: 1911, surface: 'gravel', lanes: 2, note: 'cut along the cliffs for the lighthouse and the fort' }, asphaltFrom(1934, 2), asphaltFrom(1939, 2, 'closed through the naval yard 1939-1994')],
    verts: [P(1264, -327, 108), I(1420, 120, 220, 98), I(1700, 450, 160, 86), I(1960, 700, 140, 78), I(1990, 1010, 180, 80), I(1930, 1250, 120, 76), I(1990, 1500, 130, 80), I(1880, 1720, 110, 74), I(1900, 1960, 150, 70), I(1800, 2170, 120, 60), I(1820, 2400, 140, 52), C(1650, 2690, 28, 'R', 40), I(1430, 2300, 160, 34), I(1330, 1900, 200, 30), I(1200, 1500, 250, 26), I(1100, 1050, 180, 22), I(990, 700, 150, 18), I(800, 380, 120, 10), P(480, 190, 6)],
    note: 'round the Head on the cliff tops, the lighthouse loop at the point, back along the bay side to the quay (7 km)',
  },
  {
    id: 'tunnel', name: 'Headland Tunnel', cls: 'expressway', width: 15, design: 90, emax: 0.05, lv: 200, maxGrade: 0.04,
    built: 1984, ends: ['tunnelWest', 'eastcove'],
    eras: [asphaltFrom(1984, 4, 'twin bores')],
    spans: [{ near: [1180, 115], half: 430, kind: 'tunnel' }],
    verts: [P(560, 150, 7), I(1100, 110, 1500, 9), I(1800, 160, 900, 10), I(2200, 260, 400, 8), P(2700, 300, 6)],
    note: 'under the Neck in one long curve (R 1,500), so the container trucks stop climbing Neck Hill',
  },
  {
    id: 'bayway', name: 'The Bayway', cls: 'expressway', width: 16, design: 100, emax: 0.06, lv: 160, maxGrade: 0.05,
    built: 1966, gone: 2018, ends: ['knot', 'tunnelWest'],
    eras: [{ from: 1966, to: 2018, surface: 'concrete', lanes: 4, note: 'elevated over the quay; pulled down after the 2017 quake damage' }, { from: 2021, surface: 'asphalt', lanes: 4, note: 'Bayside Boulevard on the ground in its place' }],
    spans: [{ near: [-150, 80], half: 600, kind: 'elevated' }],
    verts: [P(-893, 310, 5), I(-650, 80, 350, 14), I(-100, 80, 500, 15), I(400, 120, 600, 13), P(560, 150, 7)],
    note: 'the 1960s elevated expressway over the harbour: R 400-600 at 100 km/h, 9 m up, from the Knot to the tunnel mouth',
  },
  {
    id: 'pass', name: 'Pass Road', cls: 'arterial', width: 8, design: 80, emax: 0.07, lv: 120, maxGrade: 0.07,
    built: 1832, ends: ['crownFoot', 'passNorth'],
    eras: [{ from: 1832, surface: 'dirt', lanes: 2, note: 'the turnpike over the pass' }, { from: 1912, surface: 'macadam', lanes: 2 }, asphaltFrom(1934, 2)],
    verts: [P(-649, -1028, 75), I(-900, -1300, 200, 80), I(-1040, -1900, 160, 92), I(-1230, -2350, 220, 110), I(-1250, -2900, 300, 135), P(-1560, -3300, 160)],
    note: 'up the Cleft\'s east side under the rock walls, sweepers over the river, on towards the pass',
  },
  {
    id: 'coastwest', name: 'Coast Highway', cls: 'arterial', width: 10, design: 100, emax: 0.06, lv: 160, maxGrade: 0.05,
    built: 1928, ends: ['strandEnd', 'coastWest'],
    eras: [asphaltFrom(1928, 2), asphaltFrom(1964, 4)],
    verts: [P(-2693, 1963, 5), I(-3100, 2150, 700, 9), P(-3700, 2420, 10)],
    note: 'west along the coast to the next towns',
  },
  {
    id: 'steep', name: 'Steep Street', cls: 'lane', width: 6, design: 30, emax: 0, lv: 15, maxGrade: 0.12,
    built: 1790, ends: ['steepFoot', 'steepTop'],
    eras: [{ from: 1790, surface: 'cobbles', lanes: 1, note: 'one way uphill from 1961' }],
    verts: [P(0, 200, 5), I(20, 0, 25, 16), I(-60, -180, 20, 31), I(30, -330, 18, 46), I(-50, -480, 15, 62), I(10, -600, 20, 76), P(-80, -720, 85)],
    note: 'the old town\'s twisting cobbled climb, R 15-25, 6 m between the walls',
  },
  {
    id: 'crownlane', name: 'Crown Lane', cls: 'lane', width: 6, design: 40, emax: 0.03, lv: 25, maxGrade: 0.1,
    built: 1810, ends: ['steepTop', 'crownFoot'],
    eras: [{ from: 1810, surface: 'cobbles', lanes: 2 }, asphaltFrom(1960, 2)],
    verts: [P(-80, -720, 85), I(-250, -820, 40, 88), I(-450, -900, 60, 82), P(-649, -1028, 75)],
    note: 'along the Crown\'s skirt from the old town to the switchbacks\' foot',
  },
];

export interface BuiltSpan { from: number; to: number; kind: Span['kind'] }
export interface BuiltRoad { spec: RoadSpec; a: Alignment; spans: BuiltSpan[] }

export function buildRoads(): BuiltRoad[] {
  return ROADS.map((spec) => {
    const a = align(spec.verts, { lv: spec.lv, vDesign: spec.design / 3.6, emax: spec.emax });
    const spans = (spec.spans ?? []).map((sp) => {
      let best = Infinity, at = 0;
      for (const q of a.samples) {
        const d = Math.hypot(q.x - sp.near[0], q.z - sp.near[1]);
        if (d < best) { best = d; at = q.s; }
      }
      return { from: Math.max(0, at - sp.half), to: Math.min(a.length, at + sp.half), kind: sp.kind };
    });
    return { spec, a, spans };
  });
}

/** The loop's roads in order. */
export function loopRoads(roads: BuiltRoad[]): BuiltRoad[] {
  return roads.filter((r) => r.spec.loop !== undefined).sort((a, b) => a.spec.loop! - b.spec.loop!);
}

// ------------------------------------------------------------------ the New Town's grid (generated)

export interface Street { id: string; name: string; a: XZ; b: XZ; along: boolean }

/** The New Town: a grid on the plain, laid out square to the Esplanade's chord (1905), blocks jittered by the seed. */
export function newTownGrid(seed = CITY2_SEED, poly: readonly XZ[]): Street[] {
  const R = (...k: (string | number)[]): number => rand(seed, 'newtown', ...k);
  const ang = Math.atan2(2055 - 210, -2696 + 720); // the chord from Fountain Circus to Strand End
  const ux = Math.cos(ang), uz = Math.sin(ang), wx = -uz, wz = ux;
  const o: XZ = [-720, 210];
  const inside = (x: number, z: number): boolean => {
    let c = false;
    for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
      const [xi, zi] = poly[i]!, [xj, zj] = poly[j]!;
      if ((zi > z) !== (zj > z) && x < ((xj - xi) * (z - zi)) / (zj - zi) + xi) c = !c;
    }
    return c;
  };
  const out: Street[] = [];
  const line = (id: string, name: string, along: boolean, c: number, lo: number, hi: number): void => {
    // clip the line to the polygon by stepping along it
    let start: number | null = null;
    const step = 10;
    for (let t = lo; t <= hi + step; t += step) {
      const x = along ? o[0] + ux * t + wx * c : o[0] + ux * c + wx * t;
      const z = along ? o[1] + uz * t + wz * c : o[1] + uz * c + wz * t;
      const inn = t <= hi && inside(x, z);
      if (inn && start === null) start = t;
      if (!inn && start !== null) {
        const e = t - step;
        if (e - start > 60) {
          const pt = (q: number): XZ => (along ? [o[0] + ux * q + wx * c, o[1] + uz * q + wz * c] : [o[0] + ux * c + wx * q, o[1] + uz * c + wz * q]);
          out.push({ id: `${id}:${out.length}`, name, a: pt(start), b: pt(e), along });
        }
        start = null;
      }
    }
  };
  // streets parallel to the sea, every 130-170 m inland of the Esplanade
  const alongNames = ['Marine Parade', 'Victoria Street', 'Albion Street', 'Mercer Street', 'Gorse Street', 'Upper Street', 'Fell Street'];
  let c = 150;
  for (let i = 0; i < alongNames.length; i++) { c += 130 + R('a', i) * 40; line('nt-a', alongNames[i]!, true, c, -200, 3200); }
  // cross streets every 160-210 m
  let t = 120;
  for (let i = 0; t < 3000; i++) { line('nt-x', `${ordinal(i + 1)} Avenue`, false, t, 0, 1600); t += 160 + R('x', i) * 50; }
  return out;
}

function ordinal(n: number): string {
  const w = ['First', 'Second', 'Third', 'Fourth', 'Fifth', 'Sixth', 'Seventh', 'Eighth', 'Ninth', 'Tenth', 'Eleventh', 'Twelfth', 'Thirteenth', 'Fourteenth', 'Fifteenth', 'Sixteenth', 'Seventeenth', 'Eighteenth'];
  return w[n - 1] ?? `${n}th`;
}
