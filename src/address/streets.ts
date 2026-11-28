import { archetypeOf, type ArchetypeId } from '../sim/archetype';
import { SURNAMES } from '../sim/names';
import { rand } from '../sim/rng';
import { buildRoads, newTownGrid } from '../town/city2/roads';
import { CITY2_SEED, DISTRICTS } from '../town/city2/site';
import type { FarmPlan } from '../town/farms';
import type { Road } from '../town/kinds';
import type { TownPlan } from '../town/plan';
import { PLATTED, isCapital, type Block, type District } from '../town/plat';
import { recipeOf } from '../town/recipe';
import type { CardinalDir, StreetBlockBreak, StreetKind, StreetRecord, StreetRename } from './types';

// ---- Original street name vocabularies (no real people, brands, or real places) ----

const TREE_NAMES = [
  'Oak', 'Maple', 'Elm', 'Cedar', 'Pine', 'Birch', 'Willow', 'Sycamore', 'Alder', 'Walnut',
  'Chestnut', 'Spruce', 'Linden', 'Ash', 'Poplar', 'Hemlock', 'Mulberry', 'Rowan', 'Hawthorn', 'Juniper',
  'Hickory', 'Beech', 'Cypress', 'Dogwood', 'Laurel', 'Magnolia', 'Redbud', 'Tamarack', 'Catalpa', 'Larch',
] as const;

const ARCHETYPE_STREETS: Record<ArchetypeId, readonly string[]> = {
  farm: [
    'Meadow', 'Clover', 'Orchard', 'Harvest', 'Barley', 'Thresher', 'Pasture', 'Furrow', 'Grange', 'Hayfield',
    'Cloverleaf', 'Silo', 'Flax', 'Windmill', 'Bramble', 'Cornfield',
  ],
  harbor: [
    'Harbor', 'Wharf', 'Anchorage', 'Beacon', 'Tidewater', 'Schooner', 'Mariner', 'Breakwater', 'Cove', 'Seawall',
    'Mooring', 'Shipyard', 'Saltmarsh', 'Lantern', 'Pelican', 'Osprey',
  ],
  mill: [
    'Mill', 'Foundry', 'Weir', 'Loom', 'Tannery', 'Forge', 'Quarry', 'Tailrace', 'Spindle', 'Ironworks',
    'Flume', 'Millrace', 'Sawyer', 'Cooper', 'Carding', 'Wheelwright',
  ],
  mining: [
    'Ore', 'Smelter', 'Shaft', 'Canyon', 'Granite', 'Quartz', 'Lode', 'Tipple', 'Copper', 'Redrock',
    'Shale', 'Assay', 'Trestle', 'Pickaxe', 'Slag', 'Ironstone',
  ],
  resort: [
    'Overlook', 'Summit', 'Crestview', 'Pinecrest', 'Lakeview', 'Sunridge', 'Promenade', 'Alpenglow', 'Terrace', 'Clearwater',
    'Belvedere', 'Breeze', 'Starlight', 'Lookout', 'Balsam', 'Fernwood',
  ],
  suburb: [
    'Parkview', 'Woodland', 'Briarwood', 'Meadowbrook', 'Hillcrest', 'Fairway', 'Crestwood', 'Brookside', 'Greenbriar', 'Oakridge',
    'Heather', 'Kestrel', 'Wren', 'Foxglove', 'Thornhill', 'Stonegate',
  ],
};

const SETTING_STREETS: Record<string, readonly string[]> = {
  alpine: ['Timberline', 'Glacier', 'Switchback', 'Edelweiss', 'Highpass', 'Sprucecrest', 'Snowmelt', 'Cragview'],
  pass: ['Stagecoach', 'Tollgate', 'Saddleback', 'Gap', 'Divide', 'Switchback', 'Wagoner', 'Highpass'],
  mining: ['Silvervein', 'Stamper', 'Prospector', 'Driftwood', 'Colliery', 'Brimstone', 'Tunnel', 'Tailings'],
};

const COUNTRY_LANE_WORDS = [
  'Mill', 'Quarry', 'Orchard', 'Creek', 'Ridge', 'Ferry', 'Post', 'Wagon', 'Stage', 'Church',
  'Timber', 'Hollow', 'Bridge', 'Spring', 'Meadow', 'Stone', 'Gravel', 'Limekiln', 'Tannery', 'Ford',
  'Pasture', 'Cider', 'Sawmill', 'Blacksmith',
] as const;

const GROUNDS_STREETS = [
  'Meridian Parkway', 'Commons Boulevard', 'Ballpark Drive', 'Campus Road', 'Fairground Avenue',
  'Pavilion Way', 'Monument Drive', 'Fieldhouse Lane', 'Stadium Parkway', 'Esplanade Drive',
] as const;

const ORDINALS = [
  'First', 'Second', 'Third', 'Fourth', 'Fifth', 'Sixth', 'Seventh', 'Eighth', 'Ninth', 'Tenth',
  'Eleventh', 'Twelfth', 'Thirteenth', 'Fourteenth', 'Fifteenth', 'Sixteenth', 'Seventeenth', 'Eighteenth',
  'Nineteenth', 'Twentieth',
] as const;

const EARLY_SUFFIXES = ['Street', 'Avenue', 'Street', 'Lane', 'Road'] as const;
const POSTWAR_SUFFIXES = ['Drive', 'Road', 'Terrace', 'Boulevard', 'Avenue'] as const;
const LATE_SUFFIXES = ['Court', 'Place', 'Way', 'Circle', 'Trail'] as const;
const STUB_SUFFIXES = ['Court', 'Place', 'Circle', 'Way'] as const;

const DIR_PREFIX: Record<CardinalDir, string> = { N: 'N.', S: 'S.', E: 'E.', W: 'W.' };

export function ordinalWord(n: number): string {
  return ORDINALS[n - 1] ?? `${n}th`;
}

/** Return the active full street name in [year], applying all historical renames up to [year]. */
export function streetNameIn(street: Pick<StreetRecord, 'name' | 'renames'>, year: number): string {
  if (!street.renames.length) return street.name;
  // Walk renames: if year < first rename year, return first.fromName; otherwise the latest rename with r.year <= year.
  const sorted = [...street.renames].sort((a, b) => a.year - b.year);
  if (year < sorted[0]!.year) return sorted[0]!.fromName;
  let active = sorted[0]!.toName;
  for (const r of sorted) {
    if (r.year <= year) active = r.toName;
    else break;
  }
  return active;
}

/** Return the active base street name (without N./S./E./W. directional prefix) in [year]. */
export function baseStreetNameIn(street: Pick<StreetRecord, 'name' | 'direction' | 'renames'>, year: number): string {
  const full = streetNameIn(street, year);
  return full.replace(/^(?:[NSEW]\.\s+|(?:North|South|East|West)\s+)/i, '');
}

/** Return every name a street has ever carried (current and historical). */
export function allKnownStreetNames(street: Pick<StreetRecord, 'name' | 'renames'>): string[] {
  const out = new Set<string>([street.name]);
  for (const r of street.renames) {
    out.add(r.fromName);
    out.add(r.toName);
  }
  return [...out];
}

interface RawLine {
  horizontal: boolean;
  /** Fixed axis coordinate (centerline y for horizontal, x for vertical). */
  coord: number;
  lo: number;
  hi: number;
  roadIndices: number[];
  classes: string[];
  main: boolean;
  primaryClass: string;
  district: District;
  builtYear: number;
}

function dominantDistrict(plan: TownPlan, horizontal: boolean, coord: number, lo: number, hi: number): District {
  const blocks = plan.v2?.blocks ?? [];
  const counts = new Map<District, number>();
  for (const b of blocks) {
    const near = horizontal
      ? Math.abs(b.y - (coord + 1)) <= 4 || Math.abs(b.y + b.h - (coord - 1)) <= 4
      : Math.abs(b.x - (coord + 1)) <= 4 || Math.abs(b.x + b.w - (coord - 1)) <= 4;
    if (!near) continue;
    const overlap = horizontal
      ? Math.min(hi, b.x + b.w) - Math.max(lo, b.x)
      : Math.min(hi, b.y + b.h) - Math.max(lo, b.y);
    if (overlap <= 0) continue;
    counts.set(b.district, (counts.get(b.district) ?? 0) + overlap);
  }
  if (!counts.size) return 'old';
  let best: District = 'old';
  let bestYear = Infinity;
  let bestScore = -1;
  for (const [d, sc] of counts) {
    if (d === 'river' && counts.size > 1) continue;
    const yr = PLATTED[d] ?? 1900;
    if (yr < bestYear || (yr === bestYear && sc > bestScore)) {
      bestYear = yr;
      bestScore = sc;
      best = d;
    }
  }
  return best;
}

function primaryRoadClass(classes: readonly string[], main: boolean): string {
  if (main || classes.includes('main')) return 'main';
  for (const priority of ['bypass', 'depot', 'court', 'railroad', 'south', 'river'] as const) {
    if (classes.includes(priority)) return priority;
  }
  if (classes.every((c) => c === 'stub')) return 'stub';
  return 'street';
}

function groupTownRoads(plan: TownPlan): RawLine[] {
  const roadClass = plan.v2?.roadClass ?? plan.roads.map((r) => (r.main ? 'main' : 'street'));
  const buckets = new Map<string, { idx: number; r: Road; cls: string }[]>();

  plan.roads.forEach((r, idx) => {
    const coord = r.horizontal ? Math.round(r.y + r.h / 2) : Math.round(r.x + r.w / 2);
    const key = `${r.horizontal ? 'H' : 'V'}:${coord}`;
    const arr = buckets.get(key) ?? [];
    arr.push({ idx, r, cls: roadClass[idx] ?? 'street' });
    buckets.set(key, arr);
  });

  const lines: RawLine[] = [];
  for (const [key, segs] of [...buckets.entries()].sort((a, b) => (a[0] < b[0] ? -1 : 1))) {
    const horizontal = key.startsWith('H:');
    const coord = Number(key.slice(2));
    segs.sort((a, b) => (horizontal ? a.r.x - b.r.x : a.r.y - b.r.y));

    // Split collinear segments into physically contiguous lines (a 2-tile cross street leaves gap <= 4; larger gaps split)
    let cur: typeof segs = [];
    const flush = (): void => {
      if (!cur.length) return;
      const lo = horizontal ? cur[0]!.r.x : cur[0]!.r.y;
      const hi = horizontal ? Math.max(...cur.map((s) => s.r.x + s.r.w)) : Math.max(...cur.map((s) => s.r.y + s.r.h));
      const classes = cur.map((s) => s.cls);
      const main = cur.some((s) => s.r.main);
      const pCls = primaryRoadClass(classes, main);
      const district = dominantDistrict(plan, horizontal, coord, lo, hi);
      const builtYear = pCls === 'main' ? 1850 : PLATTED[district] ?? 1890;
      lines.push({
        horizontal,
        coord,
        lo,
        hi,
        roadIndices: cur.map((s) => s.idx),
        classes,
        main,
        primaryClass: pCls,
        district,
        builtYear,
      });
      cur = [];
    };

    for (const s of segs) {
      if (!cur.length) {
        cur.push(s);
        continue;
      }
      const prevHi = horizontal ? Math.max(...cur.map((q) => q.r.x + q.r.w)) : Math.max(...cur.map((q) => q.r.y + q.r.h));
      const nextLo = horizontal ? s.r.x : s.r.y;
      const prevStub = cur.every((q) => q.cls === 'stub');
      const nextStub = s.cls === 'stub';
      // Keep contiguous segments merged across 2-tile crossings; split across real gaps or stub/through transitions across gaps
      if (nextLo - prevHi <= 4 && !(prevStub !== nextStub && nextLo - prevHi > 2)) {
        cur.push(s);
      } else {
        flush();
        cur.push(s);
      }
    }
    flush();
  }
  return lines;
}

/** Compute block breaks along one addressed half/full straight street based on crossing roads. */
function computeGridBlocks(
  plan: TownPlan,
  horizontal: boolean,
  coord: number,
  origin: { x: number; y: number },
  dir: number, // -1 or +1 along the street axis
  length: number,
  startBlock = 100,
): StreetBlockBreak[] {
  const crossings: number[] = [];
  for (const r of plan.roads) {
    if (r.horizontal === horizontal) continue;
    // Does this perpendicular road cross or meet our street centerline?
    const perpSpanLo = r.horizontal ? r.x : r.y;
    const perpSpanHi = r.horizontal ? r.x + r.w : r.y + r.h;
    if (coord < perpSpanLo - 3 || coord > perpSpanHi + 3) continue;
    const crossCoord = r.horizontal ? r.y + r.h / 2 : r.x + r.w / 2;
    const d = horizontal ? (crossCoord - origin.x) * dir : (crossCoord - origin.y) * dir;
    if (d > 8 && d < length - 4) crossings.push(d);
  }
  crossings.sort((a, b) => a - b);

  // Deduplicate crossings within 8 tiles and subdivide very long unbroken stretches (> 65 tiles)
  const breaks: StreetBlockBreak[] = [{ along: 0, blockNumber: startBlock }];
  let lastD = 0;
  let blockNum = startBlock;
  for (const d of crossings) {
    if (d - lastD < 10) continue;
    while (d - lastD > 68) {
      lastD += 45;
      blockNum += 100;
      breaks.push({ along: lastD, blockNumber: blockNum });
    }
    blockNum += 100;
    breaks.push({ along: d, blockNumber: blockNum });
    lastD = d;
  }
  while (length - lastD > 68) {
    lastD += 45;
    blockNum += 100;
    breaks.push({ along: lastD, blockNumber: blockNum });
  }
  return breaks;
}

function suffixFor(district: District, kind: StreetKind, seed: string, key: string): string {
  const r = rand(seed, 'street-suffix', key);
  if (kind === 'stub') return STUB_SUFFIXES[Math.floor(r * STUB_SUFFIXES.length)]!;
  if (kind === 'bypass') return 'Road';
  if (kind === 'river') return r < 0.5 ? 'Road' : 'Drive';
  if (district === 'heights') return r < 0.4 ? 'Terrace' : 'Drive';
  if (district === 'late' || district === 'edge') return LATE_SUFFIXES[Math.floor(r * LATE_SUFFIXES.length)]!;
  if (district === 'postwar' || district === 'strip') return POSTWAR_SUFFIXES[Math.floor(r * POSTWAR_SUFFIXES.length)]!;
  return EARLY_SUFFIXES[Math.floor(r * EARLY_SUFFIXES.length)]!;
}

/** Build all deterministic, unique, historically-aware StreetRecords for a grid/platted town + its farm country roads. */
export function buildTownStreets(
  plan: TownPlan,
  farmPlan: FarmPlan | null,
  townDisplayName: string,
  foundedYear: number,
): StreetRecord[] {
  const seed = plan.seed;
  const archetype = archetypeOf(seed).id;
  const recipe = recipeOf(seed);
  const lines = groupTownRoads(plan);
  const usedNames = new Set<string>();
  const usedRoots = new Set<string>();

  const claim = (candidate: string): string | null => {
    const norm = candidate.toLowerCase();
    if (usedNames.has(norm)) return null;
    usedNames.add(norm);
    return candidate;
  };

  // Build deterministic shuffled pools per category
  const shuffle = <T,>(items: readonly T[], salt: string): T[] =>
    [...items]
      .map((item, i) => ({ item, k: rand(seed, 'pool', salt, i) }))
      .sort((a, b) => a.k - b.k)
      .map((x) => x.item);

  const regionalBase: string[] = [
    ...ARCHETYPE_STREETS[archetype],
    ...(recipe?.setting && SETTING_STREETS[recipe.setting] ? SETTING_STREETS[recipe.setting]! : []),
    ...(recipe?.ore ? [recipe.ore.charAt(0).toUpperCase() + recipe.ore.slice(1)] : []),
  ];
  const regionalPool = shuffle([...new Set(regionalBase)], 'regional');
  const treePool = shuffle(TREE_NAMES, 'trees');
  const founderPool = shuffle(SURNAMES, 'founders');
  const countryPool = shuffle(COUNTRY_LANE_WORDS, 'country');
  const groundsPool = [...GROUNDS_STREETS];

  const takeUniqueName = (
    pool: string[],
    suffix: string,
    fallbackPools: string[][],
    salt: string,
  ): string => {
    const allPools = [pool, ...fallbackPools];
    for (const p of allPools) {
      for (const word of p) {
        const root = word.toLowerCase();
        if (usedRoots.has(root)) continue;
        const full = `${word} ${suffix}`;
        if (claim(full)) {
          usedRoots.add(root);
          return full;
        }
      }
    }
    // Second pass: allow reusing a root with a different suffix if a huge town exhausts all roots
    const altSuffixes = ['Street', 'Avenue', 'Road', 'Drive', 'Lane', 'Way', 'Place', 'Court', 'Terrace'];
    for (const p of allPools) {
      for (const word of p) {
        for (const sfx of altSuffixes) {
          const full = `${word} ${sfx}`;
          if (claim(full)) return full;
        }
      }
    }
    let n = 1;
    while (true) {
      const full = `${ordinalWord(n)} ${suffix}`;
      if (claim(full)) return full;
      n++;
    }
  };

  // Identify numbered grid cross-streets: vertical regular 'street' lines (not grounds, not stub, not structural)
  const numberedCandidates = lines
    .filter((l) => !l.horizontal && l.primaryClass === 'street' && l.district !== 'grounds')
    .sort((a, b) => Math.abs(a.coord - plan.center.x) - Math.abs(b.coord - plan.center.x) || a.coord - b.coord);
  const useNumberedCross = numberedCandidates.length >= 3;
  const numberedMap = new Map<RawLine, string>();
  if (useNumberedCross) {
    // Number cross streets west-to-east (or from civic outward) cleanly as First Street, Second Street, ...
    const ordered = [...numberedCandidates].sort((a, b) => a.coord - b.coord);
    ordered.forEach((line, i) => {
      const name = `${ordinalWord(i + 1)} Street`;
      if (claim(name)) numberedMap.set(line, name);
    });
  }

  // Sort lines deterministically: main first, structural next, then center-outward
  const priorityRank = (l: RawLine): number => {
    if (l.primaryClass === 'main') return 0;
    if (l.primaryClass === 'court') return 1;
    if (l.primaryClass === 'depot') return 2;
    if (l.primaryClass === 'railroad') return 3;
    if (l.primaryClass === 'south') return 4;
    if (l.primaryClass === 'river') return 5;
    if (l.primaryClass === 'bypass') return 6;
    if (l.district === 'grounds') return 7;
    if (l.primaryClass === 'stub') return 9;
    return 8;
  };

  const sortedLines = [...lines].sort(
    (a, b) =>
      priorityRank(a) - priorityRank(b) ||
      Math.hypot((a.horizontal ? (a.lo + a.hi) / 2 : a.coord) - plan.center.x, (a.horizontal ? a.coord : (a.lo + a.hi) / 2) - plan.center.y) -
        Math.hypot((b.horizontal ? (b.lo + b.hi) / 2 : b.coord) - plan.center.x, (b.horizontal ? b.coord : (b.lo + b.hi) / 2) - plan.center.y) ||
      a.coord - b.coord ||
      a.lo - b.lo,
  );

  const streets: StreetRecord[] = [];
  let renameCount = 0;
  const maxRenames = 3;

  const pickRenameOldName = (candidates: string[]): string | null => {
    for (const c of candidates) {
      if (claim(c)) return c;
    }
    return null;
  };

  for (let li = 0; li < sortedLines.length; li++) {
    const line = sortedLines[li]!;
    let baseName = numberedMap.get(line) ?? '';
    let kind: StreetKind = (line.primaryClass as StreetKind) || 'street';
    const baseRenames: StreetRename[] = [];

    if (!baseName) {
      if (line.primaryClass === 'main') {
        baseName = claim('Main Street') ?? takeUniqueName(regionalPool, 'Street', [treePool, founderPool], `m:${li}`);
        kind = 'main';
      } else if (line.primaryClass === 'court') {
        baseName =
          claim(plan.v2?.countySeat ? 'Court Street' : 'Square Street') ??
          claim('Court Street') ??
          claim('Market Street') ??
          takeUniqueName(founderPool, 'Street', [regionalPool, treePool], `ct:${li}`);
        kind = 'court';
      } else if (line.primaryClass === 'depot') {
        baseName =
          claim('Depot Street') ??
          claim('Station Street') ??
          claim('Depot Avenue') ??
          takeUniqueName(regionalPool, 'Street', [founderPool, treePool], `dp:${li}`);
        kind = 'depot';
        // Historical rename: before the railroad arrived (1888), Depot Street was Livery Lane / Stage Lane
        if (foundedYear < 1888 && renameCount < maxRenames) {
          const old = pickRenameOldName(['Livery Lane', 'Stagecoach Lane', 'Dray Street']);
          if (old) {
            baseRenames.push({
              year: 1888,
              fromName: old,
              toName: baseName,
              reason: 'Renamed when the railroad depot opened',
            });
            renameCount++;
          }
        }
      } else if (line.primaryClass === 'railroad') {
        baseName =
          claim('Railroad Avenue') ??
          claim('Freight Street') ??
          claim('Trackside Road') ??
          claim('Siding Street') ??
          takeUniqueName(regionalPool, 'Avenue', [founderPool, treePool], `rr:${li}`);
        kind = 'railroad';
      } else if (line.primaryClass === 'south') {
        baseName =
          claim('South Street') ??
          claim('Crossing Road') ??
          claim('Millside Street') ??
          takeUniqueName(regionalPool, 'Street', [treePool, founderPool], `so:${li}`);
        kind = 'south';
      } else if (line.primaryClass === 'river') {
        baseName =
          claim('River Street') ??
          claim('Riverside Drive') ??
          claim('Water Street') ??
          claim('Bankside Lane') ??
          takeUniqueName(regionalPool, 'Drive', [treePool, founderPool], `rv:${li}`);
        kind = 'river';
      } else if (line.primaryClass === 'bypass') {
        baseName =
          claim('Bypass Road') ??
          claim('Commerce Parkway') ??
          claim('Beltway Drive') ??
          takeUniqueName(regionalPool, 'Road', [founderPool, treePool], `bp:${li}`);
        kind = 'bypass';
        const bypassYear = recipe?.bypassed?.year ?? (1958 + Math.floor(rand(seed, 'bypass-rename') * 12));
        if (renameCount < maxRenames) {
          const old = pickRenameOldName(['Old Boundary Road', 'East County Road', 'Old Plank Road']);
          if (old) {
            baseRenames.push({
              year: bypassYear,
              fromName: old,
              toName: baseName,
              reason: 'Widened and renamed as the town highway bypass',
            });
            renameCount++;
          }
        }
      } else if (line.district === 'grounds' && isCapital(seed)) {
        let picked: string | null = null;
        for (const g of groundsPool) {
          if (claim(g)) {
            picked = g;
            break;
          }
        }
        baseName = picked ?? takeUniqueName(regionalPool, 'Parkway', [treePool, founderPool], `gr:${li}`);
        kind = 'boulevard';
      } else if (line.primaryClass === 'stub') {
        const sfx = suffixFor(line.district, 'stub', seed, `${li}`);
        baseName = takeUniqueName(treePool, sfx, [regionalPool, founderPool], `st:${li}`);
        kind = 'stub';
      } else {
        const sfx = suffixFor(line.district, 'street', seed, `${li}`);
        // Mix trees, founders, and regional names by era and orientation
        const styleRoll = rand(seed, 'street-theme', li, line.coord);
        if (line.horizontal) {
          // Horizontal streets favor trees in old/postwar/suburb neighborhoods, regional in harbor/mining/mill
          if (styleRoll < 0.55) {
            baseName = takeUniqueName(treePool, sfx, [regionalPool, founderPool], `h:${li}`);
          } else if (styleRoll < 0.8) {
            baseName = takeUniqueName(regionalPool, sfx, [treePool, founderPool], `h:${li}`);
          } else {
            baseName = takeUniqueName(founderPool, sfx, [treePool, regionalPool], `h:${li}`);
          }
        } else {
          if (styleRoll < 0.45) {
            baseName = takeUniqueName(founderPool, sfx, [regionalPool, treePool], `v:${li}`);
          } else if (styleRoll < 0.78) {
            baseName = takeUniqueName(treePool, sfx, [founderPool, regionalPool], `v:${li}`);
          } else {
            baseName = takeUniqueName(regionalPool, sfx, [treePool, founderPool], `v:${li}`);
          }
        }
        kind = sfx === 'Avenue' ? 'avenue' : sfx === 'Boulevard' ? 'boulevard' : 'street';

        // Give one non-main older street a historical civic/founder rename in the mid-20th century
        if (
          renameCount < maxRenames &&
          (line.district === 'core' || line.district === 'old' || line.district === 'westmain' || line.district === 'civic') &&
          rand(seed, 'civic-rename', li) < 0.22
        ) {
          const old = pickRenameOldName(['Old Tannery Lane', 'Mill Race Lane', 'Market Alley', 'Old Plank Street', 'Wagon Road']);
          if (old) {
            const yr = 1948 + Math.floor(rand(seed, 'civic-rename-yr', li) * 32);
            baseRenames.push({
              year: yr,
              fromName: old,
              toName: baseName,
              reason: 'Renamed by town council civic ordinance',
            });
            renameCount++;
          }
        }
      }
    }

    // Check if this street line crosses the town's origin axis (split into W/E or N/S halves)
    const originAxis = line.horizontal ? plan.center.x : plan.center.y;
    const crossesOrigin = line.lo < originAxis - 6 && line.hi > originAxis + 6;
    const lineIdBase = `st:${line.horizontal ? 'h' : 'v'}:${line.coord}:${line.lo}`;

    if (crossesOrigin) {
      const halves: { dir: CardinalDir; sign: -1 | 1; length: number }[] = line.horizontal
        ? [
            { dir: 'W', sign: -1, length: originAxis - line.lo },
            { dir: 'E', sign: 1, length: line.hi - originAxis },
          ]
        : [
            { dir: 'N', sign: -1, length: originAxis - line.lo },
            { dir: 'S', sign: 1, length: line.hi - originAxis },
          ];

      for (const h of halves) {
        const origin = line.horizontal ? { x: originAxis, y: line.coord } : { x: line.coord, y: originAxis };
        const dirVec = line.horizontal ? { x: h.sign, y: 0 } : { x: 0, y: h.sign };
        const prefix = DIR_PREFIX[h.dir];
        const fullName = `${prefix} ${baseName}`;
        const renames: StreetRename[] = baseRenames.map((r) => ({
          year: r.year,
          fromName: `${prefix} ${r.fromName}`,
          toName: `${prefix} ${r.toName}`,
          reason: r.reason,
        }));
        const segIndices = line.roadIndices.filter((idx) => {
          const r = plan.roads[idx]!;
          const segLo = line.horizontal ? r.x : r.y;
          const segHi = line.horizontal ? r.x + r.w : r.y + r.h;
          return h.sign < 0 ? segLo < originAxis : segHi > originAxis;
        });
        streets.push({
          id: `${lineIdBase}:${h.dir}`,
          baseName,
          direction: h.dir,
          name: fullName,
          kind,
          district: line.district,
          builtYear: Math.min(line.builtYear, foundedYear + 20),
          renames,
          roadIndices: segIndices,
          origin,
          dirVec,
          length: h.length,
          blocks: computeGridBlocks(plan, line.horizontal, line.coord, origin, h.sign, h.length, 100),
        });
      }
    } else {
      // Entirely on one side of the origin axis: orient away from the origin!
      const dLo = Math.abs(line.lo - originAxis);
      const dHi = Math.abs(line.hi - originAxis);
      const sign: -1 | 1 = dLo <= dHi ? 1 : -1;
      const startCoord = sign > 0 ? line.lo : line.hi;
      const origin = line.horizontal ? { x: startCoord, y: line.coord } : { x: line.coord, y: startCoord };
      const dirVec = line.horizontal ? { x: sign, y: 0 } : { x: 0, y: sign };
      const length = line.hi - line.lo;
      // Starting hundred-block reflects how far out from the origin axis this street begins
      const blocksFromOrigin = Math.max(0, Math.floor(Math.min(dLo, dHi) / 48));
      const startBlock = (1 + blocksFromOrigin) * 100;
      streets.push({
        id: lineIdBase,
        baseName,
        name: baseName,
        kind,
        district: line.district,
        builtYear: line.builtYear,
        renames: baseRenames,
        roadIndices: line.roadIndices,
        origin,
        dirVec,
        length,
        blocks: computeGridBlocks(plan, line.horizontal, line.coord, origin, sign, length, startBlock),
      });
    }
  }

  // ---- Add country lanes & rural spokes from planFarms(plan) ----
  if (farmPlan) {
    const cleanTown = townDisplayName.replace(/\s+/g, ' ').trim();
    for (let fi = 0; fi < farmPlan.roads.length; fi++) {
      const cr = farmPlan.roads[fi]!;
      const dx = cr.b[0] - cr.a[0];
      const dy = cr.b[1] - cr.a[1];
      const len = Math.hypot(dx, dy);
      if (len < 1) continue;

      // Orient away from town center
      const dA = Math.hypot(cr.a[0] - plan.center.x, cr.a[1] - plan.center.y);
      const dB = Math.hypot(cr.b[0] - plan.center.x, cr.b[1] - plan.center.y);
      const flip = cr.kind === 'ring' && dB < dA;
      const p0 = flip ? cr.b : cr.a;
      const p1 = flip ? cr.a : cr.b;
      const ux = (p1[0] - p0[0]) / len;
      const uy = (p1[1] - p0[1]) / len;

      let baseName = '';
      if (cr.kind === 'spoke') {
        const dirWord: Record<string, string> = { N: 'North', S: 'South', E: 'East', W: 'West' };
        const candidates = [
          `Old ${cleanTown} Road`,
          `${dirWord[cr.side] ?? ''} ${cleanTown} Pike`.trim(),
          `Old ${countryPool[fi % countryPool.length]!} Road`,
        ];
        for (const c of candidates) {
          if (claim(c)) {
            baseName = c;
            break;
          }
        }
      }
      if (!baseName) {
        // Country lanes: "Old ... Road"
        for (const word of countryPool) {
          const cand = `Old ${word} Road`;
          if (claim(cand)) {
            baseName = cand;
            break;
          }
        }
      }
      if (!baseName) {
        baseName = takeUniqueName(treePool, 'Road', [founderPool, regionalPool], `cr:${fi}`);
      }

      const startBlock = cr.kind === 'spoke' ? 1000 : 2000;
      const blocks: StreetBlockBreak[] = [];
      for (let d = 0, b = startBlock; d < len; d += 40, b += 100) {
        blocks.push({ along: d, blockNumber: b });
      }

      streets.push({
        id: `country:${cr.id}`,
        baseName,
        name: baseName,
        kind: cr.kind === 'ring' ? 'ring' : 'country',
        district: 'farms',
        builtYear: foundedYear,
        renames: [],
        roadIndices: [],
        origin: { x: p0[0], y: p0[1] },
        dirVec: { x: ux, y: uy },
        length: len,
        blocks,
      });
    }
  }

  return streets;
}

/** Historical renames for Dove Beach's named roads, grounded in city2/roads.ts notes. */
const DOVE_BEACH_RENAMES: Record<string, StreetRename[]> = {
  fellside: [
    {
      year: 1931,
      fromName: 'Cleft Turnpike',
      toName: 'Fellside Road',
      reason: 'Renamed when the Fellside Viaduct opened across the Sallow Cleft',
    },
  ],
  pass: [
    {
      year: 1934,
      fromName: 'Old Pass Turnpike',
      toName: 'Pass Road',
      reason: 'Paved and redesignated as Pass Road',
    },
  ],
  causeway: [
    {
      year: 1923,
      fromName: 'Old Tramway Road',
      toName: 'Marsh Causeway',
      reason: 'Rebuilt as an asphalt causeway with the Heron Creek swing bridge',
    },
  ],
  esplanade: [
    {
      year: 1924,
      fromName: 'Marine Carriage Drive',
      toName: 'The Esplanade',
      reason: 'Widened and macadamized as the seafront promenade',
    },
  ],
};

function pointInPoly(x: number, z: number, poly: readonly (readonly [number, number])[]): boolean {
  let c = false;
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    const [xi, zi] = poly[i]!;
    const [xj, zj] = poly[j]!;
    if ((zi > z) !== (zj > z) && x < ((xj - xi) * (z - zi)) / (zj - zi) + xi) c = !c;
  }
  return c;
}

export function doveBeachDistrictAt(x: number, z: number): { id: string; name: string } {
  for (const d of DISTRICTS) {
    if (pointInPoly(x, z, d.poly)) return { id: d.id, name: d.name };
  }
  // Nearest district centroid fallback
  let best = DISTRICTS[0]!;
  let bestD = Infinity;
  for (const d of DISTRICTS) {
    const cx = d.poly.reduce((s, p) => s + p[0], 0) / d.poly.length;
    const cz = d.poly.reduce((s, p) => s + p[1], 0) / d.poly.length;
    const dist = Math.hypot(x - cx, z - cz);
    if (dist < bestD) {
      bestD = dist;
      best = d;
    }
  }
  return { id: best.id, name: best.name };
}

/** Build all deterministic StreetRecords for Dove Beach (city2 named curved roads + Seagrove New Town grid). */
export function buildDoveBeachStreets(seed = CITY2_SEED): StreetRecord[] {
  const originPt = { x: 0, y: 140 }; // Custom House Square (the dove's eye)
  const built = buildRoads();
  const streets: StreetRecord[] = [];
  const used = new Set<string>();

  for (const br of built) {
    const pts0 = br.a.samples;
    if (pts0.length < 2) continue;
    // Orient polyline from the end closer to Custom House Square [0, 140] outward
    const first = pts0[0]!;
    const last = pts0[pts0.length - 1]!;
    const dFirst = Math.hypot(first.x - originPt.x, first.z - originPt.y);
    const dLast = Math.hypot(last.x - originPt.x, last.z - originPt.y);
    const reversed = dLast < dFirst;
    const ordered = reversed ? [...pts0].reverse() : pts0;

    // Subsample polyline (~20 m spacing, keeping exact endpoints) for compact JSON & fast projection
    const poly: [number, number, number][] = [];
    let cum = 0;
    let prev = ordered[0]!;
    poly.push([Math.round(prev.x * 100) / 100, Math.round(prev.z * 100) / 100, 0]);
    for (let i = 1; i < ordered.length; i++) {
      const p = ordered[i]!;
      cum += Math.hypot(p.x - prev.x, p.z - prev.z);
      prev = p;
      const lastStored = poly[poly.length - 1]!;
      if (i === ordered.length - 1 || cum - lastStored[2] >= 20) {
        poly.push([Math.round(p.x * 100) / 100, Math.round(p.z * 100) / 100, Math.round(cum * 100) / 100]);
      }
    }

    const totalLen = poly[poly.length - 1]![2];
    const pStart = poly[0]!;
    const pEnd = poly[poly.length - 1]!;
    const spanLen = Math.hypot(pEnd[0] - pStart[0], pEnd[1] - pStart[1]) || 1;
    const mid = poly[Math.floor(poly.length / 2)]!;
    const dist = doveBeachDistrictAt(mid[0], mid[1]);

    const blocks: StreetBlockBreak[] = [];
    for (let s = 0, b = 100; s <= totalLen; s += 100, b += 100) {
      blocks.push({ along: s, blockNumber: b });
    }

    used.add(br.spec.name.toLowerCase());
    const renames = DOVE_BEACH_RENAMES[br.spec.id] ?? [];
    for (const r of renames) used.add(r.fromName.toLowerCase());

    streets.push({
      id: `city2:${br.spec.id}`,
      baseName: br.spec.name,
      name: br.spec.name,
      kind: br.spec.cls as StreetKind,
      district: dist.id,
      builtYear: br.spec.built,
      ...(br.spec.gone !== undefined ? { goneYear: br.spec.gone } : {}),
      renames,
      roadIndices: [],
      origin: { x: pStart[0], y: pStart[1] },
      dirVec: { x: (pEnd[0] - pStart[0]) / spanLen, y: (pEnd[1] - pStart[1]) / spanLen },
      length: totalLen,
      blocks,
      polyline: poly,
    });
  }

  // Seagrove (New Town) grid streets from src/town/city2/roads.ts
  const newtown = DISTRICTS.find((d) => d.id === 'newtown');
  if (newtown) {
    const grid = newTownGrid(seed, newtown.poly);
    const fountain: [number, number] = [-720, 210];
    // Merge or disambiguate any duplicate grid street name if clipped into >1 segment
    const seenGrid = new Map<string, number>();
    for (const g of grid) {
      const count = (seenGrid.get(g.name) ?? 0) + 1;
      seenGrid.set(g.name, count);
      const uniqueName = count === 1 && !used.has(g.name.toLowerCase()) ? g.name : `${g.name} West`;
      used.add(uniqueName.toLowerCase());

      const dA = Math.hypot(g.a[0] - fountain[0], g.a[1] - fountain[1]);
      const dB = Math.hypot(g.b[0] - fountain[0], g.b[1] - fountain[1]);
      const p0 = dA <= dB ? g.a : g.b;
      const p1 = dA <= dB ? g.b : g.a;
      const len = Math.hypot(p1[0] - p0[0], p1[1] - p0[1]) || 1;
      const blocks: StreetBlockBreak[] = [];
      for (let s = 0, b = 100; s <= len; s += 150, b += 100) {
        blocks.push({ along: s, blockNumber: b });
      }
      streets.push({
        id: `city2:${g.id}`,
        baseName: uniqueName,
        name: uniqueName,
        kind: 'grid',
        district: 'newtown',
        builtYear: 1905,
        renames: [],
        roadIndices: [],
        origin: { x: p0[0], y: p0[1] },
        dirVec: { x: (p1[0] - p0[0]) / len, y: (p1[1] - p0[1]) / len },
        length: len,
        blocks,
        polyline: [
          [Math.round(p0[0] * 100) / 100, Math.round(p0[1] * 100) / 100, 0],
          [Math.round(p1[0] * 100) / 100, Math.round(p1[1] * 100) / 100, Math.round(len * 100) / 100],
        ],
      });
    }
  }

  return streets;
}
