import { rand } from '../../sim/rng';
import type { XZ } from './align';

// THE SECOND CITY'S SITE (kotlin-street/docs/CITY2.md): a harbour city at the head of a bell-shaped bay. The land is
// authored as features (the coast, the river and its gorge, the hills) and turned into a height field by one pure
// function of the position, with a little seeded noise on top. Metres, x east, z south, y up; 0 at sea level; the origin
// is the Custom House on the old quay.
//
//  - THE BELL: the bay, 2.9 km across, opening south; the Long Strand beach on its west shore, the Head (a headland 1 km
//    wide, 2.7 km long, cliffs on its seaward side) on its east;
//  - THE SALLOW: the river comes down from the interior through a gorge (the Cleft) between the Western Fells and the
//    Crown, and meets the bay at the old harbour;
//  - THE CROWN: the city's hill (245 m) north of the old town, a ridge running east from it to the Neck behind the Head;
//  - THE WESTERN FELLS beyond the coastal plain, the Uplands to the north, where the Pass Road climbs to the interior.

export const CITY2_SEED = 'city2';

/** The coastline, west to east, the sea to its south (and round the Head). */
export const COAST: XZ[] = [
  [-4200, 2850], [-3500, 2560], [-2900, 2280], [-2560, 2060], [-2300, 1880], [-2110, 1590], [-1760, 1350], [-1540, 1040],
  [-1200, 850], [-940, 520], [-700, 380], [-400, 280], [-250, 205], [450, 200], [650, 300], [850, 650], [1000, 1250],
  [1200, 1950], [1450, 2550], [1640, 2880], [1780, 2790], [1800, 2600], [1960, 2420], [1880, 2230], [2040, 2000],
  [1960, 1780], [2120, 1560], [2020, 1330], [2150, 1080], [2060, 860], [2170, 620], [2300, 380], [2800, 330],
  [3600, 450], [4400, 650],
];

/** Heron Marsh: the salt marsh behind the beach west of Strand End (cordgrass at half a metre, the creeks under the tide). */
export const MARSH: XZ[] = [[-2780, 2180], [-2700, 1500], [-3000, 1150], [-3700, 1100], [-4500, 1250], [-4500, 2700], [-3600, 2480], [-3000, 2250]];
/** Its tidal creeks (Heron Creek winds through to the sea west of the beach). */
export const CREEKS: XZ[][] = [
  [[-4500, 1500], [-4100, 1620], [-3800, 1560], [-3600, 1700], [-3430, 1840], [-3300, 2000], [-3350, 2200], [-3250, 2380], [-3180, 2520]],
  [[-3800, 1560], [-3900, 1350], [-3700, 1220]],
  [[-3300, 2000], [-3050, 1900], [-2900, 1700], [-2950, 1450]],
];
/** The dunes: a band behind the Long Strand's beach (sea oats, boardwalk crossings every 200 m). */
export const DUNES: XZ[] = [[-760, 340], [-1100, 610], [-1300, 830], [-1700, 1080], [-1880, 1360], [-2250, 1600], [-2450, 1900], [-2700, 2080]];
/** The Strand Pier (1909): 480 m out from Pavilion Bend, the pavilion at its end. */
export const PIER: XZ[] = [[-1600, 1010], [-1274, 1360]];

/** The harbour's two breakwater moles (1868), meeting in a V off the harbour mouth with the entrance at its point. */
export const MOLES: { name: string; line: XZ[] }[] = [
  { name: 'West Mole', line: [[-390, 265], [-215, 700]] },
  { name: 'East Mole', line: [[-60, 215], [-165, 690]] },
];

/** The Sallow, from the interior (north) to its mouth at the harbour, with its bed's height at each point. */
export const RIVER: { at: XZ; y: number }[] = [
  { at: [-1650, -3600], y: 150 }, { at: [-1350, -2500], y: 85 }, { at: [-1150, -1700], y: 40 }, { at: [-1000, -1000], y: 14 },
  { at: [-800, -400], y: 4 }, { at: [-520, 0], y: 1 }, { at: [-380, 260], y: -1 },
];

interface Hill { at: XZ; h: number; sx: number; sz: number }
/** The hills (anisotropic bumps): the Crown, the ridge to the Neck, the Head, the Fells, the Uplands. */
export const HILLS: Hill[] = [
  { at: [-300, -1400], h: 190, sx: 330, sz: 800 },  // the Crown (a whaleback, its west face square to the switchbacks)
  { at: [100, -1560], h: 30, sx: 250, sz: 250 },    // the saddle to the ridge
  { at: [505, -1600], h: 150, sx: 300, sz: 280 },    // the ridge, east of the Crown
  { at: [913, -1420], h: 115, sx: 260, sz: 250 },
  { at: [1142, -1092], h: 100, sx: 240, sz: 240 },
  { at: [1445, -917], h: 85, sx: 240, sz: 240 },
  { at: [1300, -300], h: 70, sx: 400, sz: 330 },    // the Neck
  { at: [1580, 1250], h: 85, sx: 400, sz: 950 },    // the Head
  { at: [1640, 2450], h: 50, sx: 260, sz: 320 },    // Lighthouse Point
  { at: [-3600, -900], h: 320, sx: 900, sz: 900 },  // the Western Fells
  { at: [-2200, -1700], h: 140, sx: 450, sz: 600 },
  { at: [-500, -2900], h: 200, sx: 700, sz: 700 },  // the Uplands
];

function segDist(px: number, pz: number, a: XZ, b: XZ): { d: number; t: number; side: number } {
  const dx = b[0] - a[0], dz = b[1] - a[1];
  const l2 = dx * dx + dz * dz;
  const t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((px - a[0]) * dx + (pz - a[1]) * dz) / l2));
  const qx = a[0] + dx * t, qz = a[1] + dz * t;
  // (the side: + to the line's left looking along it, which is north of a west-to-east coast: the land)
  const side = Math.sign(dx * (pz - a[1]) - dz * (px - a[0])) || 1;
  return { d: Math.hypot(px - qx, pz - qz), t, side: -side };
}

/** The coast's cliffs: the Head's seaward side, Lighthouse Point and the cove walls (segments of COAST, from..to). */
export const CLIFF_SEGMENTS: [number, number][] = [[17, 30]];

/** Signed distance to the coast: + on land, - at sea (m); [seg] the nearest segment. */
export function coastNearest(x: number, z: number): { d: number; seg: number } {
  let best = Infinity, side = 1, seg = 0;
  for (let i = 0; i + 1 < COAST.length; i++) {
    const r = segDist(x, z, COAST[i]!, COAST[i + 1]!);
    if (r.d < best) { best = r.d; side = r.side; seg = i; }
  }
  return { d: best * side, seg };
}
export const coastDistance = (x: number, z: number): number => coastNearest(x, z).d;
const isCliff = (seg: number): boolean => CLIFF_SEGMENTS.some(([a, b]) => seg >= a && seg <= b);

/** Distance to the river and its bed's height there. */
export function riverAt(x: number, z: number): { d: number; bed: number } {
  let best = Infinity, bed = 0;
  for (let i = 0; i + 1 < RIVER.length; i++) {
    const a = RIVER[i]!, b = RIVER[i + 1]!;
    const r = segDist(x, z, a.at, b.at);
    if (r.d < best) { best = r.d; bed = a.y + (b.y - a.y) * r.t; }
  }
  return { d: best, bed };
}

export function inside(x: number, z: number, poly: readonly XZ[]): boolean {
  let c = false;
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    const [xi, zi] = poly[i]!, [xj, zj] = poly[j]!;
    if ((zi > z) !== (zj > z) && x < ((xj - xi) * (z - zi)) / (zj - zi) + xi) c = !c;
  }
  return c;
}

const smooth = (e0: number, e1: number, v: number): number => {
  const t = Math.max(0, Math.min(1, (v - e0) / (e1 - e0)));
  return t * t * (3 - 2 * t);
};

/** Value noise in [-1, 1] on a [cell] m lattice, from the seed. */
function noise(x: number, z: number, cell: number, key: string): number {
  const gx = Math.floor(x / cell), gz = Math.floor(z / cell);
  const fx = x / cell - gx, fz = z / cell - gz;
  const v = (i: number, j: number): number => rand(CITY2_SEED, key, i, j) * 2 - 1;
  const sx = fx * fx * (3 - 2 * fx), sz = fz * fz * (3 - 2 * fz);
  const a = v(gx, gz) + (v(gx + 1, gz) - v(gx, gz)) * sx;
  const b = v(gx, gz + 1) + (v(gx + 1, gz + 1) - v(gx, gz + 1)) * sx;
  return a + (b - a) * sz;
}

/** The natural ground's height (m above the sea) before any road is cut into it. */
export function groundHeight(x: number, z: number): number {
  // the marsh: flat at half a metre, its creeks cut a metre and a half under the tide
  if (inside(x, z, MARSH)) {
    let dc = Infinity, de = Infinity;
    for (const c of CREEKS) for (let i = 0; i + 1 < c.length; i++) dc = Math.min(dc, segDist(x, z, c[i]!, c[i + 1]!).d);
    for (let i = 0; i < MARSH.length; i++) de = Math.min(de, segDist(x, z, MARSH[i]!, MARSH[(i + 1) % MARSH.length]!).d);
    const m = 0.5 + 0.15 * noise(x, z, 60, 'marsh') - 2 * (1 - smooth(6, 16, dc));
    if (de > 200) return m;
    // (near its edge the marsh rises into the land round it)
    const k = smooth(0, 200, de);
    return m * k + (1 - k) * landHeight(x, z);
  }
  return landHeight(x, z);
}

function landHeight(x: number, z: number): number {
  // the moles: a stone wall 18 m wide, 4 m over the sea
  for (const m of MOLES) if (segDist(x, z, m.line[0]!, m.line[1]!).d < 9) return 4;
  const cn = coastNearest(x, z);
  const cd = cn.d;
  if (cd < 0) return Math.max(-60, cd * (isCliff(cn.seg) ? 0.4 : 0.05));
  let h = 0;
  for (const k of HILLS) {
    const u = (x - k.at[0]) / k.sx, w = (z - k.at[1]) / k.sz;
    h += k.h * Math.exp(-0.5 * (u * u + w * w));
  }
  // the land rising gently inland from the coastal plain to the hills' feet
  h += 40 * smooth(400, 2500, cd);
  // rolling ground: more of it inland (the crests and dips the roads ride), little on the beach plain
  h += (6 + 12 * smooth(300, 1500, cd)) * noise(x, z, 420, 'n1') + (3 + 4 * smooth(300, 1500, cd)) * noise(x, z, 170, 'n2');
  // the shore: a beach rising to the land over 350 m, or a cliff (the land's height kept to 25 m from the water, then
  // a sheer drop: the renderer's low-poly cliff faces)
  h = isCliff(cn.seg) ? 1 + Math.max(0, h) * smooth(0, 25, cd) : 2 + Math.max(0, h) * smooth(0, 350, cd);
  // the Sallow's valley and gorge
  const r = riverAt(x, z);
  const half = 18, valley = r.bed > 10 ? 110 : 220;
  h = r.bed + (Math.max(h, r.bed) - r.bed) * smooth(half, half + valley, r.d);
  return h;
}

// ------------------------------------------------------------------ districts and landmarks

export interface District { id: string; name: string; from: number; what: string; poly: XZ[] }

export const DISTRICTS: District[] = [
  { id: 'harbour', name: 'Old Harbour', from: 1780, what: 'the little fishing harbour inside the moles: shrimp boats, the fish market, seafood shacks, the Custom House (the dove\'s eye)', poly: [[-380, 255], [480, 215], [520, 60], [-360, 60]] },
  { id: 'oldtown', name: 'Old Town', from: 1800, what: 'steep lanes and stairs up the Crown, pastel houses with dovecotes on the roofs, churches, the funicular foot', poly: [[-360, 60], [520, 60], [700, -200], [300, -720], [-200, -820], [-470, -300]] },
  { id: 'mills', name: 'Sallow Mills', from: 1860, what: 'the old rice and timber mills and the brewery in the gorge mouth (lofts, a taproom and studios from the 1990s)', poly: [[-980, -1000], [-660, -1000], [-470, -300], [-380, 60], [-560, 80], [-820, -420]] },
  { id: 'newtown', name: 'Seagrove', from: 1905, what: 'the grid behind the dunes: raised beach cottages on stilts in pastel colours, live oaks and palms, sand lanes, golf carts', poly: [[-850, 170], [-1000, 330], [-1700, 820], [-2150, 1250], [-2480, 1430], [-2560, 1150], [-2500, 560], [-2330, 220], [-2050, -80], [-1500, -300], [-1150, -260]] },
  { id: 'strand', name: 'The Strand', from: 1905, what: 'the beach strip: the Esplanade, the dunes and boardwalks, the pier, ice cream, surf and tackle shops, seafood shacks, small motels', poly: [[-700, 300], [-1000, 480], [-1300, 800], [-1660, 1000], [-1880, 1300], [-2230, 1540], [-2440, 1830], [-2720, 2050], [-2660, 1900], [-2400, 1690], [-2200, 1400], [-1860, 1170], [-1640, 850], [-1300, 660], [-1060, 330], [-760, 160]] },
  { id: 'fellside', name: 'Fellside', from: 1948, what: 'post-war cottages on the Fells\' lower slopes, the old motor court and the bait shop on Fellside Road', poly: [[-2560, 1600], [-2900, 1700], [-3000, 600], [-2700, -100], [-2200, -450], [-1800, -620], [-1700, -420], [-2050, -80], [-2330, 220], [-2500, 560], [-2560, 1150]] },
  { id: 'crown', name: 'Crown Heights', from: 1894, what: 'villas round the switchbacks, the Observatory on the summit, the funicular\'s top station', poly: [[-700, -980], [-200, -850], [20, -1300], [-180, -1780], [-520, -1720], [-720, -1300]] },
  { id: 'ridge', name: 'The Ridge', from: 1936, what: 'Ridge Park, big houses, the radio mast, the Belvedere overlook', poly: [[20, -1300], [600, -1380], [1250, -650], [1560, -950], [950, -1720], [250, -1820]] },
  { id: 'eastcove', name: 'Eastcove', from: 1952, what: 'the marina and the shrimp dock in the sheltered cove, boatyards, a fish camp; the tunnel\'s east end', poly: [[1750, -80], [2700, 60], [3100, 330], [2300, 370], [2110, 620], [1800, 420]] },
  { id: 'head', name: 'The Head', from: 1790, what: 'the old fort on the Neck, the naval yard (1939-1994), the lighthouse, cliff-top parkland', poly: [[840, 330], [2090, 420], [2060, 2000], [1660, 2840], [1300, 2350], [1010, 1250]] },
  { id: 'marsh', name: 'Heron Marsh', from: 1700, what: 'salt marsh and tidal creeks behind the beach; the causeway across it, the creek dock and its shrimp boats, sunsets', poly: MARSH },
  { id: 'uplands', name: 'Uplands', from: 1978, what: 'later houses in the hills along the Pass Road, the school, a small shopping strip', poly: [[-1950, -1650], [-1300, -1250], [-1080, -2250], [-1650, -3050], [-2250, -2300]] },
];

export interface Landmark { id: string; name: string; at: XZ; from: number; to?: number; who: 'Sam' | 'Jeff' | 'Claude'; task: string }

export const LANDMARKS: Landmark[] = [
  { id: 'observatory', name: 'Crown Observatory', at: [-280, -1500], from: 1894, who: 'Sam', task: 'domed stone observatory and keeper\'s house on the summit; the dome turns at night' },
  { id: 'funicular', name: 'Crown Funicular', at: [-150, -900], from: 1899, who: 'Jeff', task: 'a two-car funicular from the Old Town to the summit (track, stations, the ride as a real ride)' },
  { id: 'customhouse', name: 'Custom House', at: [0, 140], from: 1838, who: 'Sam', task: 'the harbour\'s stone custom house, a round clock window facing the bay (the dove\'s eye on the city crest)' },
  { id: 'pavilion', name: 'Strand Pier and Pavilion', at: [-1274, 1360], from: 1909, who: 'Sam', task: 'a 480 m timber fishing pier on piles with a white open-sided pavilion at its end (dances, then fishing; rebuilt after storms)' },
  { id: 'fountain', name: 'Dove Fountain', at: [-720, 210], from: 1912, who: 'Sam', task: 'the roundabout\'s fountain: a bronze dove with spread wings over a shell basin, palms round the circus' },
  { id: 'lighthouse', name: 'Head Light', at: [1660, 2860], from: 1851, who: 'Sam', task: 'a black-and-white banded lighthouse and its keepers\' cottages on Lighthouse Point (the right wingtip)' },
  { id: 'fort', name: 'Neck Fort', at: [1050, 0], from: 1790, who: 'Sam', task: 'an old coastal fort on the Neck with concrete gun batteries (1898-1945) along the cliff; a museum from 1960' },
  { id: 'railviaduct', name: 'Cleft Viaduct (rail)', at: [-1080, -1420], from: 1886, who: 'Claude', task: 'a curved stone arch viaduct carrying the main line over the Sallow (engine geometry: arches by code)' },
  { id: 'roadviaduct', name: 'Fellside Viaduct', at: [-990, -965], from: 1931, who: 'Claude', task: 'the concrete road viaduct beside it, 770 m long, up to 85 m over the river (engine geometry with the road mesh)' },
  { id: 'tunnel', name: 'Headland Tunnel portals', at: [560, 150], from: 1942, who: 'Sam', task: 'wartime concrete portals with blast walls, painted with a dove mural when the road opened (1984)' },
  { id: 'belvedere', name: 'The Belvedere', at: [1400, -560], from: 1936, who: 'Sam', task: 'a stone overlook with a balustrade and coin telescopes at the Ridge Drive\'s big bend' },
  { id: 'mast', name: 'Ridge Mast', at: [560, -1640], from: 1952, who: 'Sam', task: 'the radio and TV mast with red lights' },
  { id: 'shrimpdock', name: 'Eastcove shrimp dock', at: [2500, 260], from: 1952, who: 'Sam', task: 'shrimp boats with their outriggers and nets, an ice house, the marina\'s floating docks' },
  { id: 'station', name: 'Harbour Station', at: [-230, 90], from: 1886, who: 'Sam', task: 'the terminus: a train shed and booking hall by the quay' },
  { id: 'brewery', name: 'Sallow Brewery', at: [-700, -500], from: 1871, who: 'Sam', task: 'brick brewery with a chimney on the river (lofts and a taproom from 1996)' },
  { id: 'drawbridge', name: 'Heron Creek drawbridge', at: [-3430, 1840], from: 1923, who: 'Claude', task: 'the causeway\'s bascule bridge over the creek, its tender\'s house (engine geometry, it lifts for boats)' },
  { id: 'creekdock', name: 'Creek Dock', at: [-3300, 2000], from: 1890, who: 'Sam', task: 'a timber dock on Heron Creek with shrimp boats and a seafood shack on stilts' },
  { id: 'dunes', name: 'Dune crossings', at: [-2000, 1420], from: 1930, who: 'Sam', task: 'dunes with sea oats, boardwalk crossings with rails, beach showers, lifeguard stands (a kit along the Strand)' },
  { id: 'cottages', name: 'Raised beach cottages', at: [-1700, 600], from: 1905, who: 'Sam', task: 'a kit of pastel cottages on stilts with screened porches, outside stairs and tin roofs (Seagrove\'s grid)' },
];

/** The railway: the main line down the Cleft to Harbour Station, then the docks line round the quay. */
export const RAIL: XZ[] = [
  [-1750, -3600], [-1450, -2500], [-1230, -1700], [-1010, -1180], [-900, -880], [-760, -420], [-560, -80], [-300, 90], [500, 160], [900, 160], [1800, 200], [2400, 290],
];
