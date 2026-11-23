import { rand } from '../sim/rng';
import { CINEMA_SIZES, chooseLandmarks, LANDMARK_SPECS, type LandmarkKind } from './landmarks';
import { STORE_SIZES, storeNeeds } from './stores';
import { glbLandmarkTiles, type GlbLandmarkKind } from './glbLandmarks';
import { glbPlotOf, glbTenures, INTAKE_STATES, premierDepot, REPLACED_LANDMARKS, type GlbTenure } from './glbSites';
import { SIZES, type LotKind, type Rect, type Road } from './kinds';
import { PLATTED, platTown, type Block, type District, type Plat } from './plat';

// THE DISTRICTS (kotlin-street/docs/TOWN_PLAN.md "Where each building goes"): what the platted blocks (src/town/plat.ts)
// hold. First the RESERVATION LEDGER: every civic building, store and hand-made model the town will ever have gets its
// plot in its district from the plan's first year (the square's plaza or courthouse, the post office and clock tower on
// its edges, the school, fire and police behind it, the cinema opposite it, the general store and the market in the core,
// the Gazette mid-block on Main Street, the service station on the core's last corner, the strip's diner, motel, coin
// laundry and strip centre, lanes, clubs and drive-in in the order the highway grew, the supermarket on the drive-in's
// ground, the gas station at the bypass and the superstore beyond it, the depot's neighbours on the rail side, the funeral
// home on West Main, the water tower, the chapel and the cemetery). Then the DISTRICT FILLERS lay the lots (the
// existing LotKinds and sizes) along each block's street faces round the reserved plots: shop walls on Main Street,
// condos behind them, old-town houses with an alley down the block, worker houses shoulder to shoulder across the
// tracks, post-war and later homes with room between, villas on the heights. Lot ids keep the `kind:x,y` form.

export type Face = 'N' | 'S' | 'E' | 'W';

export interface PlanLot extends Rect {
  id: string;
  kind: LotKind;
  district: District;
  /** Plan order: lower fills first (district by district, nearest the centre first within one). */
  order: number;
}

/** What a reserved plot holds over the years (its chain of uses, oldest first). */
export interface PlotUse { use: string; from: number; to?: number }

export interface Reserved extends Rect {
  /** `landmark:<kind>` (civic and stores), `glb:<model>` (one plot of a model's chain), `civic:<what>`. */
  id: string;
  district: District;
  /** The face of its block it fronts (the street is on this side of it). */
  face: Face;
  /** Before, during and after (data: what the ledger says stands there; the consumers draw what they know). */
  chain: PlotUse[];
}

export interface Districts {
  lots: PlanLot[];
  reserved: Reserved[];
  alleys: Rect[];
  /** The plaza (the civic block's open ground; a county seat's courthouse square). */
  plaza: Rect;
}

const hit = (a: Rect, b: Rect, pad = 0): boolean =>
  a.x < b.x + b.w + pad && b.x - pad < a.x + a.w && a.y < b.y + b.h + pad && b.y - pad < a.y + a.h;
const inside = (a: Rect, b: Rect): boolean => a.x >= b.x && a.y >= b.y && a.x + a.w <= b.x + b.w && a.y + a.h <= b.y + b.h;


const cache = new Map<string, Districts>();

export function districtsOf(seed: string): Districts {
  const got = cache.get(seed);
  if (got) return got;
  const plat = platTown(seed);
  const out = fill(plat);
  cache.set(seed, out);
  return out;
}

function fill(p: Plat): Districts {
  const seed = p.seed;
  const R = (...k: (string | number)[]): number => rand(seed, 'districts', ...k);
  const reserved: Reserved[] = [];
  const lots: PlanLot[] = [];
  const alleys: Rect[] = [];
  const cx = p.center.x, cy = p.center.y;
  const at = (col: number, row: number): Block | undefined => p.blocks.find((b) => b.col === col && b.row === row);
  /** The block's face towards Main Street (rows -1 and 0), or towards the civic side. */
  const mainFace = (b: Block): Face => b.main ?? (b.row < 0 ? (p.flipY ? 'N' : 'S') : (p.flipY ? 'S' : 'N'));
  const back = (f: Face): Face => (f === 'N' ? 'S' : f === 'S' ? 'N' : f === 'E' ? 'W' : 'E');
  /** Which way along x is towards the strip (+1 east). */
  const toStrip = p.flipX ? -1 : 1;
  const water = p.river?.channel ?? [];
  const wet = (r: Rect, pad = 3): boolean => water.some((w) => hit(r, w, pad)) || (p.river?.column ?? []).some((c) => hit(r, { x: c.x + 2, y: c.y, w: c.w - 4, h: c.h }, 0) && !p.blocks.some((b) => b.district !== 'river' && inside(r, b)));
  const band = p.railBand;
  // (the rail band's track and its clearance, kept off by every lot: models in the band stand on its town side)
  const onTrack = (r: Rect): boolean => !!band && (band.horizontal ? r.y < band.track + 6 && r.y + r.h > band.track - 6 : r.x < band.track + 6 && r.x + r.w > band.track - 6);

  // ---------------------------------------------------------------------------------------------- the ledger
  /** A rect on face [f] of [b]: [along] x [deep], [t] tiles from the face's west (N/S) or north (E/W) end. */
  const onFace = (b: Block, f: Face, along: number, deep: number, t: number): Rect =>
    f === 'N' ? { x: b.x + t, y: b.y, w: along, h: deep } : f === 'S' ? { x: b.x + t, y: b.y + b.h - deep, w: along, h: deep }
      : f === 'W' ? { x: b.x, y: b.y + t, w: deep, h: along } : { x: b.x + b.w - deep, y: b.y + t, w: deep, h: along };
  const faceLen = (b: Block, f: Face): number => (f === 'N' || f === 'S' ? b.w : b.h);
  /** Positions along a face, in the order a slot of kind [pos] tries them. */
  const positions = (b: Block, f: Face, along: number, pos: 'near' | 'far' | 'mid' | 'strip' | 'core', toward?: { x: number; y: number }): number[] => {
    const n = faceLen(b, f) - along;
    if (n < 0) return [];
    const all = Array.from({ length: n + 1 }, (_, i) => i);
    const centre = (t: number): number => (f === 'N' || f === 'S' ? b.x + t + along / 2 : b.y + t + along / 2);
    const c0 = f === 'N' || f === 'S' ? (toward?.x ?? cx) : (toward?.y ?? cy);
    if (pos === 'mid') return all.sort((a, q) => Math.abs(a - n / 2) - Math.abs(q - n / 2) || a - q);
    if (pos === 'near') return all.sort((a, q) => Math.abs(centre(a) - c0) - Math.abs(centre(q) - c0) || a - q);
    if (pos === 'far') return all.sort((a, q) => Math.abs(centre(q) - c0) - Math.abs(centre(a) - c0) || a - q);
    // towards the strip end (east, or west when mirrored), or towards the core
    const s = (f === 'N' || f === 'S') ? toStrip : 1;
    return all.sort((a, q) => (pos === 'strip' ? s * (q - a) : s * (a - q)));
  };
  const free = (r: Rect, b: Block, except: string[] = [], pad = 1): boolean =>
    inside(r, b) && !reserved.some((q) => !except.includes(q.id) && hit(r, q, pad)) && !wet(r) && !onTrack(r);
  /**
   * Reserve [id] in the first of [blocks] (and faces) where a plot [along] x [deep] fits at the first free position of
   * kind [pos]; [sizes] more sizes to try (best first). Returns the plot, or undefined.
   */
  function reserve(id: string, chain: PlotUse[], blocks: (Block | undefined)[], faces: (b: Block) => Face[], sizes: [number, number][], pos: Parameters<typeof positions>[3], opt: { pad?: number; except?: string[]; keep?: (r: Rect) => boolean; toward?: { x: number; y: number } } = {}): Reserved | undefined {
    for (const b of blocks) {
      if (!b) continue;
      for (const f of faces(b)) {
        if (!b.faces.includes(f)) continue;
        for (const [along, deep] of sizes) {
          for (const t of positions(b, f, along, pos, opt.toward)) {
            const r = onFace(b, f, along, deep, t);
            if (!free(r, b, opt.except, opt.pad ?? 1) || (opt.keep && !opt.keep(r))) continue;
            const q: Reserved = { ...r, id, district: b.district, face: f, chain };
            reserved.push(q);
            return q;
          }
        }
      }
    }
    return undefined;
  }
  const blocksOf = (d: District | District[], row?: number): Block[] => p.blocks.filter((b) => (Array.isArray(d) ? d.includes(b.district) : b.district === d) && (row === undefined || b.row === row));
  const byNear = (bs: Block[]): Block[] => [...bs].sort((a, b) => Math.hypot(a.x + a.w / 2 - cx, a.y + a.h / 2 - cy) - Math.hypot(b.x + b.w / 2 - cx, b.y + b.h / 2 - cy) || a.col - b.col || a.row - b.row);
  /** Core blocks of row [row], from the civic block outwards towards the strip (dir +1) or away (dir -1). */
  const coreRow = (row: number): Block[] => blocksOf('core', row).sort((a, b) => a.col - b.col);
  const civic = p.civic;
  const mf = mainFace(civic);
  const tenures = glbTenures(seed);
  const has = (k: string): GlbTenure[] => tenures.filter((t) => t.kind === `landmark_glb_${k}`);
  const chainOf = (ts: GlbTenure[], before = 'field'): PlotUse[] => [{ use: before, from: 0, to: ts[0]?.opened }, ...ts.map((t) => ({ use: t.kind, from: t.opened, ...(t.gone ? { to: t.gone } : {}) }))];
  const tiles = (k: GlbLandmarkKind): [number, number] => glbLandmarkTiles(k);
  // (the capital's school campus, fire station and post office are models: the sprite landmarks they replace are not reserved)
  const replaced = new Set<string>(Object.entries(REPLACED_LANDMARKS).filter(([m]) => tenures.some((t) => glbPlotOf(t.kind) === `glb:${m}`)).map(([, k]) => k));
  const civicKinds = chooseLandmarks(seed).filter((k) => !replaced.has(k));
  const plotChain = (m: string): GlbTenure[] => tenures.filter((t) => glbPlotOf(t.kind) === `glb:${m}`);
  const needs = storeNeeds(seed);
  const spec = (k: LandmarkKind): [number, number] => [LANDMARK_SPECS[k].w, LANDMARK_SPECS[k].h];
  const lm = (k: LandmarkKind, chain: PlotUse[] = [{ use: 'woods', from: 0 }, { use: k, from: 0 }]): PlotUse[] => chain;

  // ---- the civic block
  let plaza: Rect;
  if (p.countySeat) {
    // the courthouse square: one plot, the whole block (its model brings the lawn, the memorial, the bandstand)
    const r = { x: civic.x, y: civic.y, w: civic.w, h: civic.h };
    reserved.push({ ...r, id: 'glb:county_courthouse', district: 'square', face: mf, chain: chainOf(tenures.filter((t) => t.kind.startsWith('landmark_glb_county_courthouse_')), 'square') });
    plaza = r;
  } else {
    // the town square: paved from Main Street back (a strip short of the back street, so the town hall at its back fronts
    // Main Street across it), the post office and the clock tower on the block's front corners beside it, the town hall at
    // its back; the fountain, benches, trees in grates and lamps between (plan.ts); the shops across its side streets face
    // it (the core's corner shops)
    // (the square proper between them, the whole depth; the corners behind them paved too: the export paves the block)
    const pz = onFace(civic, mf, civic.w - 20, civic.h - 3, 10);
    reserved.push({ ...pz, id: 'civic:plaza', district: 'square', face: mf, chain: [{ use: 'plaza', from: 0 }] });
    plaza = pz;
    if (civicKinds.includes('post')) reserve('landmark:post', lm('post'), [civic], () => [mf], [spec('post')], 'near', { pad: 0, keep: (r) => r.x + r.w <= pz.x - 1 });
    if (civicKinds.includes('clocktower')) reserve('landmark:clocktower', lm('clocktower'), [civic], () => [mf], [spec('clocktower')], 'near', { pad: 0, keep: (r) => r.x >= pz.x + pz.w + 1 });
  }
  const civic2 = at(0, -2);
  const behind = [civic2, at(1, -2), at(-1, -2)].filter((b): b is Block => !!b && b.district !== 'river');
  // the post office and the fire station, as models, face the square from the blocks behind it (the post office first)
  const towardSq = (b: Block): Face[] => [p.flipY ? 'N' : 'S', p.flipY ? 'S' : 'N'];
  if (replaced.has('post')) reserve('glb:post_office', chainOf(plotChain('post_office'), 'woods'), behind, towardSq, [tiles('landmark_glb_post_office_1938')], 'near');
  if (replaced.has('fire')) reserve('glb:fire_station', chainOf(plotChain('fire_station'), 'woods'), [...behind.slice(1), ...behind.slice(0, 1)], towardSq, [tiles('landmark_glb_fire_station_1924')], 'near');
  if (p.countySeat) {
    // the town hall, the post office and the fire station face the square from the block behind it
    const toSq = (b: Block): Face[] => [back(mainFace(b)) === 'N' ? 'S' : 'N'].filter((f) => b.faces.includes(f as Face)) as Face[];
    void toSq;
    const sq = (b: Block): Face[] => [p.flipY ? 'N' : 'S'];
    reserve('civic:townhall', [{ use: 'townhall', from: 1885 }], [civic2], sq, [[18, 14]], 'mid');
    if (civicKinds.includes('post')) reserve('landmark:post', lm('post'), behind, sq, [spec('post')], 'near');
    if (civicKinds.includes('fire')) reserve('landmark:fire', lm('fire'), behind, sq, [spec('fire')], 'near');
    if (civicKinds.includes('clocktower')) reserve('landmark:clocktower', lm('clocktower'), [...behind, ...byNear(blocksOf('old'))], (b) => [mainFace(b), back(mainFace(b))], [spec('clocktower')], 'near');
  } else if (civicKinds.includes('fire')) reserve('landmark:fire', lm('fire'), [civic, ...behind], (b) => (b === civic ? [back(mf)] : [p.flipY ? 'N' : 'S', p.flipY ? 'S' : 'N']), [spec('fire')], 'near');
  if (civicKinds.includes('school')) reserve('landmark:school', lm('school'), [...behind, ...byNear(blocksOf('old'))], (b) => [back(mainFace(b)), mainFace(b)], [spec('school')], 'near');
  // (the capital's police station is a model, the fifth intake's C009 (docs/queue/INTAKE.md): where the sprite one stood, the civic block's back face or the blocks behind the square)
  if (replaced.has('police')) reserve('glb:police_station', chainOf(plotChain('police_station'), 'woods'), [...(p.countySeat ? [] : [civic]), ...behind, ...byNear(blocksOf('old'))], (b) => (b === civic ? [back(mf)] : [mainFace(b), back(mainFace(b))]), [tiles('landmark_glb_police_station_1900')], 'far');
  if (civicKinds.includes('police')) reserve('landmark:police', lm('police'), [...(p.countySeat ? [] : [civic]), ...behind, ...byNear(blocksOf('old'))], (b) => (b === civic ? [back(mf)] : [mainFace(b), back(mainFace(b))]), [spec('police')], 'far');

  // ---- the core
  const north = coreRow(-1), south = coreRow(0);
  const sideOf = (b: Block): number => Math.sign((b.x + b.w / 2 - cx) * toStrip); // +1 towards the strip
  const nearCivic = (bs: Block[], side: number): Block[] => bs.filter((b) => sideOf(b) === side).sort((a, b) => Math.abs(a.col) - Math.abs(b.col));
  // the cinema opposite the civic block (the view from the square), through to the back lots
  if (civicKinds.includes('cinema')) {
    const opp = south.filter((b) => Math.abs(b.x + b.w / 2 - cx) < b.w).sort((a, b) => Math.abs(a.x + a.w / 2 - cx) - Math.abs(b.x + b.w / 2 - cx));
    reserve('landmark:cinema', lm('cinema', [{ use: 'shops', from: 0 }, { use: 'cinema', from: 0 }]), [...opp, ...south], (b) => [mainFace(b)], CINEMA_SIZES.map(([w, h]) => [w, h]), 'mid');
  }
  // a county seat's hotel faces the square; any other's is on Main Street beside the civic block
  if (civicKinds.includes('hotel')) reserve('landmark:hotel', lm('hotel', [{ use: 'shops', from: 0 }, { use: 'hotel', from: 0 }]), [...nearCivic(north, -1), ...nearCivic(north, 1), ...south], (b) => [mainFace(b)], [spec('hotel')], 'near');
  // the general store: the core's south side, west of the civic block, mid-block
  const store = reserve('landmark:generalstore', [{ use: 'generalstore', from: 0 }], [...nearCivic(south, -1), ...south], (b) => [mainFace(b)], STORE_SIZES.generalstore.map(([w, h]) => [w, h]), 'mid');
  // the market (1950s-60s): the core's last block on the strip side, at its strip end
  if (needs.market !== undefined) {
    const last = [...nearCivic(south, 1)].reverse();
    reserve('landmark:market', [{ use: 'shops', from: 0, to: needs.market }, { use: 'market', from: needs.market }], [...last, ...[...south].reverse()], (b) => [mainFace(b)], STORE_SIZES.market.map(([w, h]) => [w, h]), 'strip');
  }
  // the Gazette: mid-block on Main Street's north side, in the block the river backs onto (if it jogs) or east of the square
  const gz = has('gazette_office');
  if (gz.length) {
    const backsOnRiver = north.filter((b) => water.some((w) => hit(w, b, 0)));
    reserve('glb:gazette_office', chainOf(gz, 'shops'), [...backsOnRiver, ...nearCivic(north, 1), ...nearCivic(north, -1)], (b) => [mainFace(b)], [tiles('landmark_glb_gazette_office')], 'mid');
  }
  // the Main Street service station on the core's last corner on the strip side (north face)
  const ss = has('main_street_service_station');
  if (ss.length) reserve('glb:main_street_service_station', chainOf(ss, 'shops'), [...[...nearCivic(north, 1)].reverse(), ...[...nearCivic(south, 1)].reverse(), ...stripNearFirst()], (b) => [mainFace(b)], [tiles('landmark_glb_main_street_service_station')], 'strip');

  function stripNearFirst(): Block[] { return [...blocksOf('strip', -1), ...blocksOf('strip', 0)].sort((a, b) => Math.abs(a.col) - Math.abs(b.col) || a.row - b.row); }
  // ---- the strip, in the order the highway grew
  const stripN = blocksOf('strip', -1).sort((a, b) => sideOf(a) * Math.abs(a.col) - sideOf(b) * Math.abs(b.col));
  const stripS = blocksOf('strip', 0).sort((a, b) => sideOf(a) * Math.abs(a.col) - sideOf(b) * Math.abs(b.col));
  const outerN = [...stripN].reverse(), outerS = [...stripS].reverse();
  const strip = (k: string, blocks: Block[], pos: Parameters<typeof positions>[3], before = 'field', opt: Parameters<typeof reserve>[6] = {}): Reserved | undefined => {
    const ts = has(k);
    if (!ts.length) return undefined;
    return reserve(`glb:${k}`, chainOf(ts, before), blocks, (b) => [mainFace(b)], [tiles(ts[0]!.kind)], pos, { pad: 2, ...opt });
  };
  strip('junction_diner', stripN, 'core');
  strip('roadside_motel', stripN, 'core');
  strip('coin_laundromat', stripS, 'core');
  // the strip centre (one plot, four states), never beside a coin laundry
  const vid = tenures.filter((t) => t.kind.startsWith('landmark_glb_reel_time_video_'));
  if (vid.length) reserve('glb:reel_time_video', chainOf(vid), stripS, (b) => [mainFace(b)], [tiles(vid[0]!.kind)], 'core', { pad: 2, keep: (r) => !reserved.some((q) => q.id === 'glb:coin_laundromat' && hit(r, q, 10)) });
  strip('starling_lanes', [...stripN.slice(1), ...stripN.slice(0, 1)], 'core');
  // one club plot: Club Mirage, then a Pulse on it (the outer block's corner, north side)
  const club = [...has('mirage_nightclub'), ...has('pulse_nightclub')].sort((a, b) => a.opened - b.opened);
  if (club.length) reserve('glb:club', chainOf(club, 'reserved'), outerN, (b) => [mainFace(b)], [tiles(club[0]!.kind)], 'strip', { pad: 2 });
  // the drive-in on the strip's outer block (south side); the supermarket on its ground once it has gone
  const edgeS = blocksOf('edge', 0), edgeN = p.blocks.filter((b) => b.district === 'edge' && b.row < 0);
  strip('modern_gas_station', edgeS.length ? edgeS : outerS, 'core');
  const di = strip('starlight_drive_in', outerS, 'strip');
  if (needs.supermarket !== undefined) {
    const diGone = has('starlight_drive_in')[0]?.gone;
    const onDi = di && diGone !== undefined && diGone <= needs.supermarket;
    const chain: PlotUse[] = onDi ? [{ use: 'starlight_drive_in', from: 0, to: diGone }, { use: 'supermarket', from: needs.supermarket }] : [{ use: 'field', from: 0, to: needs.supermarket }, { use: 'supermarket', from: needs.supermarket }];
    const fallback = [...outerS, ...edgeS, ...(needs.superstore === undefined ? edgeN : [])];
    const field: PlotUse[] = [{ use: 'field', from: 0, to: needs.supermarket }, { use: 'supermarket', from: needs.supermarket }];
    // (on the drive-in's ground where it fits, keeping its corner: else a field beside it)
    const got = onDi && reserve('landmark:supermarket', chain, [p.blocks.find((b) => inside(di!, b))!], (b) => [mainFace(b)], STORE_SIZES.supermarket.map(([w, h]) => [w, h]), 'strip', { pad: 2, except: ['glb:starlight_drive_in'], keep: (r) => hit(r, di!, 0) });
    if (!got) reserve('landmark:supermarket', field, fallback, (b) => [mainFace(b)], STORE_SIZES.supermarket.map(([w, h]) => [w, h]), 'strip', { pad: 2 });
  }
  // the bypass: the 2000s gas station at its junction (south side of the highway), the superstore beyond it (north)
  if (needs.superstore !== undefined) reserve('landmark:superstore', [{ use: 'field', from: 0, to: needs.superstore }, { use: 'superstore', from: needs.superstore }], edgeN, (b) => [mainFace(b)], STORE_SIZES.superstore.map(([w, h]) => [w, h]), 'mid', { pad: 2 });

  // ---- the rail side: the depot's ground at the foot of Depot Street is the railroad's (src/town/rail.ts); the feed
  // mill on the siding away from the strip, the works towards it (a mill town's on the river, where it meets the rail side)
  const railBlocks = p.blocks.filter((b) => b.district === 'rail');
  if (band) {
    const townFace: Face = band.horizontal ? (band.townSide < 0 ? 'N' : 'S') : (band.townSide < 0 ? 'W' : 'E');
    // (the hand-made depot's 26 tiles of platform and lot, or the halt's 12, and a few tiles each side)
    const half = premierDepot(seed) ? 16 : 10;
    const depotRect: Rect = band.horizontal ? { x: band.depot.x - half, y: Math.min(band.depot.y, band.track) - 1, w: 2 * half, h: Math.abs(band.track - band.depot.y) + 2 }
      : { x: Math.min(band.depot.x, band.track) - 1, y: band.depot.y - half, w: Math.abs(band.track - band.depot.x) + 2, h: 2 * half };
    reserved.push({ ...depotRect, id: 'civic:depot', district: 'rail', face: townFace, chain: [{ use: 'depot', from: 1888 }] });
    const along = (b: Block): number => (band.horizontal ? b.x + b.w / 2 - band.depot.x : b.y + b.h / 2 - band.depot.y);
    const sideRail = (b: Block): number => Math.sign(band.horizontal ? along(b) * toStrip : along(b));
    const awayStrip = railBlocks.filter((b) => sideRail(b) <= 0).sort((a, b) => Math.abs(along(a)) - Math.abs(along(b)));
    const towardStrip = railBlocks.filter((b) => sideRail(b) >= 0).sort((a, b) => Math.abs(along(a)) - Math.abs(along(b)));
    const fm = has('feed_mill');
    if (fm.length) reserve('glb:feed_mill', chainOf(fm), [...awayStrip, ...towardStrip], () => [townFace], [tiles('landmark_glb_feed_mill')], 'near', { pad: 2 });
    const works = [...has('foundry_factory'), ...has('foundry_nightclub')];
    if (works.length) {
      const mill = p.type === 'mill' || p.type === 'mining';
      const byRiver = mill && p.river ? [...railBlocks].sort((a, b) => Math.min(...p.river!.column.map((c) => Math.abs(c.x + c.w / 2 - a.x - a.w / 2))) - Math.min(...p.river!.column.map((c) => Math.abs(c.x + c.w / 2 - b.x - b.w / 2)))) : [];
      const riverAt = mill && p.river ? p.river.column.map((c) => ({ x: c.x + c.w / 2, y: c.y + c.h / 2 })).sort((a, q) => Math.abs(a.x - cx) - Math.abs(q.x - cx))[0] : undefined;
      reserve('glb:foundry', chainOf(works), [...byRiver, ...towardStrip, ...awayStrip], () => [townFace], [tiles('landmark_glb_foundry_factory')], 'near', { pad: 2, ...(riverAt ? { toward: riverAt } : {}) });
    }
    // the Lark Bend grain elevator on the siding by the depot (the third intake): fronting the town side, its back to the track where the siding runs
    if (plotChain('grain_elevator').length) reserve('glb:grain_elevator', chainOf(plotChain('grain_elevator')), [...awayStrip, ...towardStrip], () => [townFace], [tiles('landmark_glb_grain_elevator_1950')], 'near', { pad: 2 });
    // the farm town's water tower beside the elevator
    if (p.type === 'farm' && has('water_tower').length) reserve('glb:water_tower', chainOf(has('water_tower')), [...awayStrip, ...towardStrip], () => [townFace], [tiles('landmark_glb_water_tower')], 'near', { pad: 2 });
  }
  // ---- West Main: the funeral home, a big old house on the corner of West Main and the first cross street west of downtown
  const fh = has('funeral_home');
  if (fh.length) {
    const wm = byNear([...blocksOf('westmain', -1), ...blocksOf('westmain', 0), ...blocksOf('old', -2), ...blocksOf('old')]);
    // (fronting the cross street at the corner, or the block's back street: a house lot off Main Street itself)
    const toCore = (b: Block): Face => (b.x + b.w / 2 < cx ? 'E' : 'W');
    reserve('glb:funeral_home', [{ use: 'house', from: fh[0]!.houseBuilt ?? 1915, to: fh[0]!.opened }, ...fh.map((t) => ({ use: t.kind, from: t.opened }))], wm, (b) => [back(mainFace(b)), toCore(b), mainFace(b)], [tiles('landmark_glb_funeral_home')], 'near');
  }
  // ---- the water tower on the heights' crest (else behind the civic block)
  const wt = has('water_tower');
  if (wt.length && !reserved.some((q) => q.id === 'glb:water_tower')) {
    reserve('glb:water_tower', chainOf(wt), [...byNear(blocksOf('heights')), ...behind, ...byNear(blocksOf('old'))], (b) => [back(mainFace(b)), mainFace(b)], [tiles('landmark_glb_water_tower')], 'far');
  }
  // ---- the radio station: open ground for its mast at the old town's edge or behind the strip, off the river and the rail
  const radio = has('radio_station');
  if (radio.length) {
    const ground = [...byNear(blocksOf(['postwar', 'strip'])).filter((b) => b.row !== 0 && b.row !== -1), ...byNear(blocksOf('old')).reverse(), ...byNear(blocksOf('strip'))];
    reserve('glb:radio_station', chainOf(radio), ground, (b) => [mainFace(b), back(mainFace(b))], [tiles('landmark_glb_radio_station')], 'far', { pad: 3, keep: (r) => !wet(r, 8) && !(band && (band.horizontal ? Math.abs(r.y + r.h / 2 - band.track) < 30 : Math.abs(r.x + r.w / 2 - band.track) < 30)) });
  }
  // ---- the hospital at the edge of the later subdivisions
  if (civicKinds.includes('hospital')) reserve('landmark:hospital', lm('hospital', [{ use: 'field', from: 0 }, { use: 'hospital', from: 0 }]), [...byNear(blocksOf('late')), ...byNear(blocksOf('postwar'))], (b) => [mainFace(b), back(mainFace(b))], [spec('hospital')], 'near');
  // ---- the chapel on an old-town corner near the centre, the cemetery at the old town's edge
  reserve('civic:chapel', [{ use: 'chapel', from: 1870 }], byNear(blocksOf(['old', 'civic', 'westmain'])), (b) => [back(mainFace(b)), mainFace(b)], [[5, 9]], 'mid');
  {
    const edge = [...blocksOf(['old', 'heights'])].sort((a, b) => Math.hypot(b.x + b.w / 2 - cx, b.y + b.h / 2 - cy) - Math.hypot(a.x + a.w / 2 - cx, a.y + a.h / 2 - cy) || a.col - b.col);
    for (const b of edge) {
      const f = back(mainFace(b));
      const r = onFace(b, f, b.w, Math.round(b.h / 2) - 2, 0);
      if (!free(r, b)) continue;
      reserved.push({ ...r, id: 'civic:cemetery', district: b.district, face: f, chain: [{ use: 'cemetery', from: 1885 }] });
      break;
    }
  }

  // ---- the intake models (docs/queue/INTAKE.md): the library near the square, the drugstore and the bank downtown, the churches in the old
  // town and at the edge, the carhop on the strip; the capital's school, rest area, lodge, Eyrie, arch, giants and Great Meridian on
  // its grounds (plat.ts GROUNDS: the acres they need), each fronting its street
  const toSquare = (b: Block): Face[] => [back(mainFace(b)), mainFace(b)];
  if (plotChain('public_library').length) reserve('glb:public_library', chainOf(plotChain('public_library'), 'woods'), [...behind, ...byNear(blocksOf(['old', 'civic', 'westmain']))], towardSq, [tiles('landmark_glb_public_library_1912')], 'near', { pad: 2 });
  if (plotChain('corner_drugstore').length) reserve('glb:corner_drugstore', chainOf(plotChain('corner_drugstore'), 'shops'), [...nearCivic(north, -1), ...nearCivic(north, 1), ...south], (b) => [mainFace(b)], [tiles('landmark_glb_corner_drugstore_family')], 'strip');
  if (plotChain('small_town_bank').length) reserve('glb:small_town_bank', chainOf(plotChain('small_town_bank'), 'shops'), [...nearCivic(south, -1), ...nearCivic(south, 1), ...north], (b) => [mainFace(b)], [tiles('landmark_glb_small_town_bank_1920s')], 'near');
  if (plotChain('brick_church').length) reserve('glb:brick_church', chainOf(plotChain('brick_church'), 'woods'), byNear(blocksOf(['old', 'westmain'])), toSquare, [tiles('landmark_glb_brick_church')], 'strip', { pad: 2 });
  if (plotChain('country_church').length) reserve('glb:country_church', chainOf(plotChain('country_church'), 'field'), [...byNear(blocksOf(['late', 'postwar', 'heights', 'old'])).reverse()], toSquare, [tiles('landmark_glb_country_church')], 'far', { pad: 2 });
  if (plotChain('carhop_drive_in').length) reserve('glb:carhop_drive_in', chainOf(plotChain('carhop_drive_in')), [...stripN, ...stripS], (b) => [mainFace(b)], [tiles('landmark_glb_carhop_drive_in_original')], 'core', { pad: 2 });
  // ---- the third intake's five and dime (before the grounds' reserved plot and the other future plots, which stay last in the ledger): mid-block in a street wall on Main Street's core blocks (south side, beside the bank in the block nearest the civic block's strip side, where the shops stand; the north side's first block is the start view's street wall; its party walls meet the shops beside it)
  const dime = plotChain('five_and_dime').length ? reserve('glb:five_and_dime', chainOf(plotChain('five_and_dime'), 'shops'), [...nearCivic(south, 1), ...nearCivic(north, 1).slice(1), ...nearCivic(north, 1).slice(0, 1), ...nearCivic(north, -1), ...south], (b) => [mainFace(b)], [tiles('landmark_glb_five_and_dime_1925')], 'mid', { pad: 0 }) : undefined;
  // ---- the fourth intake (C004-C006, docs/queue/INTAKE.md). The barber and the salon (5 x 11 tiles each) fill the gap beside the five and dime in its block's street wall
  // (the nearest free ground to it, party walls touching: the wall closes from the block's end to the dime); Westmeadow Elementary (69 x 32 tiles) takes ground in a late
  // subdivision's wide block; the Mill Branch school (18 x 15, in use to the 1950s, abandoned after) stands at the far west edge of the grounds, beside the ballpark's block face.
  // The truck stop (106 x 69 tiles) has no plot: no block of the plan is wide enough but the grounds' and the edge's, which are full (it stays catalogued: glbSites.ts CATALOGUED_ONLY).
  {
    const dimeBlock = dime ? p.blocks.find((b) => inside(dime, b)) : undefined;
    if (dimeBlock && dime) {
      const near = { x: dime.x + dime.w / 2, y: dime.y + dime.h / 2 };
      // (the gap is 8 tiles wide: the barber takes its nearer 5; the salon, if the gap has no room left, the next free street-wall ground nearest the dime)
      for (const m of ['barber', 'salon']) {
        if (plotChain(m).length) reserve(`glb:${m}`, chainOf(plotChain(m), 'shops'), [dimeBlock, ...nearCivic(north, 1), ...nearCivic(south, 1), ...nearCivic(north, -1), ...south], (b) => [mainFace(b)], [tiles(`landmark_glb_${m}_1940` as GlbLandmarkKind)], 'near', { pad: 0, toward: near });
      }
    }
    // the fifth intake's pawn shop (C010): a street-wall shop on a CROSS street off Main Street (the side face of a core block, its door on that street), in the core block nearest the general store
    // so it is a short walk from Main Street and the store (docs/LOOP.md); party walls are not needed here, a pad of one tile keeps the neighbouring lots' yards off its pavement
    if (plotChain('pawn_shop').length) {
      const sc = store ? { x: store.x + store.w / 2, y: store.y + store.h / 2 } : { x: cx, y: cy };
      const nearStore = [...north, ...south].sort((a, b) => Math.hypot(a.x + a.w / 2 - sc.x, a.y + a.h / 2 - sc.y) - Math.hypot(b.x + b.w / 2 - sc.x, b.y + b.h / 2 - sc.y));
      // (not on the rail column's edge, where the cross street runs along the elevator's and the mill's yards: the shop belongs among the shops)
      const offRail = nearStore.filter((b) => !band || (band.horizontal ? true : Math.abs(b.x + b.w / 2 - band.track) > 30));
      reserve('glb:pawn_shop', chainOf(plotChain('pawn_shop'), 'shops'), offRail, () => ['W', 'E'], [tiles('landmark_glb_pawn_shop_1920')], 'near', { pad: 1, toward: sc });
    }
    if (plotChain('westmeadow').length) reserve('glb:westmeadow', chainOf(plotChain('westmeadow'), 'field'), byNear(blocksOf(['late', 'postwar', 'heights'])), toSquare, [tiles('landmark_glb_westmeadow_1955')], 'near', { pad: 2 });
  }

  {
    const gb = (row: number): Block | undefined => blocksOf('grounds', row)[0];
    const onG = (id: string, m: string, row: number, pos: Parameters<typeof positions>[3], first?: GlbLandmarkKind): void => {
      const ts = plotChain(m);
      if (!ts.length) return;
      reserve(id, chainOf(ts), [gb(row)], (b) => [mainFace(b)], [tiles(first ?? ts[0]!.kind)], pos, { pad: 1 });
    };
    // north of Main Street: the Great Meridian by the town, the rest area at the far end, the lodge between; behind them the Eyrie
    onG('glb:great_meridian', 'great_meridian', -1, 'near');
    onG('glb:highway_rest_area', 'highway_rest_area', -1, 'far');
    onG('glb:lantern_pine_lodge', 'lantern_pine_lodge', -1, 'mid');
    onG('glb:the_eyrie', 'the_eyrie', -2, 'mid');
    // south: the Night Keeper by the town, the arch at the highway's far end, the Rex between; behind them the campus
    onG('glb:night_keeper', 'night_keeper', 0, 'near');
    onG('glb:union_arch', 'union_arch', 0, 'far');
    onG('glb:beach_blvd_rex', 'beach_blvd_rex', 0, 'mid');
    onG('glb:lincoln_high_school', 'lincoln_high_school', 1, 'mid');
    // (the ballpark behind the school, in the grounds' third south block: its gravel lot on the street between them)
    onG('glb:baseball_park', 'baseball_park', 2, 'mid');
    // (the Mill Branch one-room school at the far west end of the same block face, beside the ballpark: the capital's rural edge)
    onG('glb:mill_branch', 'mill_branch', 2, 'far');
    // (one more reserved landmark plot for what comes next, between the Rex and the Night Keeper)
    if (gb(0)) reserve('future:landmark:3', [{ use: 'reserved', from: 0 }], [gb(0)], (b) => [mainFace(b)], [[30, 20]], 'near', { pad: 1 });
  }

  // ---- room for what is to come (the capital and test city, CLAUDE.md "Hand-made hub towns plus procedural fill": every
  // new model goes into it as it is made): empty civic and landmark plots, reserved last, so a new kind added to the ledger
  // takes one of them (or ground after them) and never moves a plot reserved before it
  const future: [string, Block[], [number, number]][] = [
    ['future:civic:1', byNear(blocksOf(['old', 'civic', 'westmain'])), [24, 18]],
    ['future:civic:2', byNear(blocksOf(['old', 'civic', 'westmain'])), [20, 16]],
    ['future:landmark:1', byNear(blocksOf(['postwar', 'strip'])), [30, 20]],
    ['future:landmark:2', byNear(blocksOf(['late', 'edge', 'postwar'])), [40, 24]],
  ];
  for (const [id, bs, size] of future) reserve(id, [{ use: 'reserved', from: 0 }], bs, (b) => [back(mainFace(b)), mainFace(b)], [size], 'far', { pad: 2 });

  // ---------------------------------------------------------------------------------------------- the lots
  const occupied = (r: Rect): boolean => reserved.some((q) => hit(r, q, 1)) || lots.some((l) => hit(r, l, 0)) || wet(r) || onTrack(r);
  const S = (k: LotKind): [number, number] => [SIZES[k].w, SIZES[k].h];
  /** Lays lots of [kinds] (picked by the seed) along face [f] of [b], [gap] tiles apart. */
  const face = (b: Block, f: Face, kinds: LotKind[], gap: (k: LotKind, i: number) => number, rotate = false): void => {
    if (!b.faces.includes(f)) return;
    // (homes keep 2 tiles off a block's ends: a corner lot is a face's, never read as the side street's; shops run to the corner)
    const m = kinds.includes('shop') ? 0 : 2;
    const len = faceLen(b, f) - m;
    let t = m;
    for (let i = 0; t < len; i++) {
      const want = kinds[Math.floor(R('kind', b.col, b.row, f, i) * kinds.length)]!;
      const tries: LotKind[] = [want, ...kinds.filter((k) => k !== want).sort((a, c) => SIZES[c].w - SIZES[a].w)];
      let placed = false;
      for (const k of tries) {
        const [w, h] = S(k);
        // (turned to face an east or west street: 7 along it, 13 deep, as a shop on Main Street)
        const along = w, deep = h;
        if (t + along > len) continue;
        const r0 = onFace(b, f, along, deep, t);
        const r = rotate ? r0 : f === 'E' || f === 'W' ? { ...r0, w, h } : r0;
        if (occupied(r)) continue;
        lots.push({ ...r, id: `${k}:${r.x},${r.y}`, kind: k, district: b.district, order: 0 });
        t += along + gap(k, i);
        placed = true;
        break;
      }
      if (!placed) t += 1;
    }
  };
  const g = (lo: number, hi: number) => (k: LotKind, i: number): number => lo + Math.floor(R('gap', k, i, lo, hi) * (hi - lo + 1));
  const parkFrom = blocksOf('worker').length ? blocksOf('worker') : blocksOf('late');
  const mobilePark = [...parkFrom].sort((a, b) => Math.hypot(b.x + b.w / 2 - cx, b.y + b.h / 2 - cy) - Math.hypot(a.x + a.w / 2 - cx, a.y + a.h / 2 - cy) || a.col - b.col)[0];
  for (const b of p.blocks) {
    const mfb = mainFace(b), bk = back(mfb);
    const k = (...ks: LotKind[]): LotKind[] => ks;
    /** One housing kind a street face (the owner's rule, 2026-11-20), picked by the seed from [kinds]. */
    const one = (f: Face, ...kinds: LotKind[]): LotKind[] => [kinds[Math.floor(R('faceKind', b.col, b.row, f) * kinds.length)]!];
    switch (b.district) {
      case 'core': {
        face(b, mfb, k('shop'), () => 0);
        // (behind the shops, on the core's edge: apartment blocks, the only ones in town)
        face(b, bk, k('apartment'), g(0, 1));
        // the service alley behind the shops
        const ay = mfb === 'S' ? b.y + b.h - SIZES.shop.h - 3 : b.y + SIZES.shop.h;
        const alley = { x: b.x, y: ay, w: b.w, h: 3 };
        if (!wet(alley, 0)) alleys.push(alley);
        // the street wall turns every corner: shops down the side streets too, from the corner shop to the apartments at
        // the back (a county seat's blocks beside the square face it so; downtown's cross streets are shop streets)
        for (const f of ['E', 'W'] as Face[]) if (b.faces.includes(f)) face(b, f, k('shop'), () => 0, true);
        // the blocks on the river's banks turn their shops round the corner to face the riverwalk
        for (const f of ['E', 'W'] as Face[]) {
          const side = f === 'E' ? { x: b.x + b.w + 4, y: b.y, w: 6, h: b.h } : { x: b.x - 10, y: b.y, w: 6, h: b.h };
          if ((p.river?.column ?? []).some((c) => hit(side, c, 0))) face(b, f, k('shop'), () => 0, true);
        }
        break;
      }
      case 'square':
        // (the civic block is the town square: its plaza and civic buildings, no lots)
        break;
      case 'civic':
        face(b, mfb, one(mfb, 'house'), g(1, 2));
        face(b, bk, one(bk, 'house'), g(1, 2));
        break;
      case 'westmain':
        face(b, mfb, one(mfb, 'house', 'villa'), g(1, 2));
        face(b, bk, one(bk, 'house', 'villa'), g(1, 2));
        oldAlley(b);
        break;
      case 'old':
        face(b, mfb, one(mfb, 'house', 'house', 'villa'), g(1, 1));
        face(b, bk, one(bk, 'house', 'house', 'villa'), g(1, 1));
        oldAlley(b);
        break;
      case 'worker':
        // across the tracks: small houses, and the mobile home park on the town's edge
        if (b === mobilePark) { face(b, mfb, k('small', 'medium'), g(0, 1)); face(b, bk, k('small', 'medium'), g(0, 1)); }
        else { face(b, mfb, one(mfb, 'house'), g(1, 1)); face(b, bk, one(bk, 'house'), g(1, 1)); }
        break;
      case 'strip':
        // post-war stand-alone shops between the models; houses behind
        face(b, mfb, k('shop'), g(2, 3));
        face(b, bk, one(bk, 'house'), g(2, 2));
        break;
      case 'postwar':
        // ranches and small houses (the pack's family houses)
        face(b, mfb, one(mfb, 'house'), g(1, 2));
        face(b, bk, one(bk, 'house'), g(1, 2));
        break;
      case 'heights':
        face(b, mfb, one(mfb, 'villa', 'villa', 'estate'), g(2, 3));
        face(b, bk, one(bk, 'villa', 'estate'), g(2, 3));
        break;
      case 'late':
        if (b === mobilePark) { face(b, mfb, k('small', 'medium'), g(0, 1)); face(b, bk, k('small', 'medium'), g(0, 1)); break; }
        face(b, mfb, one(mfb, 'house', 'villa'), g(2, 3));
        face(b, bk, one(bk, 'house', 'villa'), g(2, 3));
        break;
      default:
        break;
    }
    // a hamlet's houses also front its cross street (the road to the next place), turned to face it
    if (p.size === 'H' && b.district !== 'square' && b.district !== 'core' && b.district !== 'strip') {
      for (const f of ['E', 'W'] as Face[]) if (b.faces.includes(f)) face(b, f, one(f, 'house', 'house', 'small'), g(2, 4), true);
    }
  }
  function oldAlley(b: Block): void {
    const mine = lots.filter((l) => inside(l, b));
    const top = Math.max(b.y, ...mine.filter((l) => l.y === b.y).map((l) => l.y + l.h));
    const bot = Math.min(b.y + b.h, ...mine.filter((l) => l.y + l.h === b.y + b.h).map((l) => l.y));
    if (bot - top < 5) return;
    const alley = { x: b.x, y: Math.round((top + bot) / 2 - 1.5), w: b.w, h: 3 };
    if (!wet(alley, 0) && !reserved.some((q) => hit(alley, q))) alleys.push(alley);
  }

  // ---- plan order: district by district, nearest the centre first within one
  const BASE: Partial<Record<District, number>> = { core: 0, square: 40, civic: 40, old: 40, westmain: 40, worker: 250, strip: 350, postwar: 450, heights: 600, late: 800, edge: 900 };
  for (const l of lots) {
    const d = Math.hypot(l.x + l.w / 2 - cx, (l.y + l.h / 2 - cy) * 1.2);
    l.order = (l.kind === 'shop' ? (l.district === 'core' || l.district === 'square' ? 0 : 100) : BASE[l.district] ?? 900) + d + R('rank', l.id) * 6;
  }
  lots.sort((a, b) => a.order - b.order || (a.id < b.id ? -1 : 1));
  // (a hamlet has no back lanes: its blocks are fields behind the houses on its two roads)
  if (p.size === 'H') alleys.length = 0;
  return { lots, reserved, alleys, plaza };
}

/** The platted year of [d] (re-exported for the consumers). */
export const plattedYear = (d: District): number => PLATTED[d];

/** The street a reserved plot or lot fronts: the road beside its face (index into [roads]), or -1. */
export function roadOnFace(roads: readonly Road[], r: Rect, face: Face): number {
  return roads.findIndex((q) => face === 'N' ? q.horizontal && q.y + q.h === r.y - 1 && q.x < r.x + r.w && r.x < q.x + q.w
    : face === 'S' ? q.horizontal && q.y === r.y + r.h + 1 && q.x < r.x + r.w && r.x < q.x + q.w
      : face === 'W' ? !q.horizontal && q.x + q.w === r.x - 1 && q.y < r.y + r.h && r.y < q.y + q.h
        : !q.horizontal && q.x === r.x + r.w + 1 && q.y < r.y + r.h && r.y < q.y + q.h);
}
