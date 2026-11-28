import { rand } from '../../sim/rng';
import type { LotKind } from '../kinds';
import {
  generateLots,
  type City2Lot,
  type City2LotLayout,
  type DoveBeachDistrictId,
  type LandmarkPlot,
  type XZ,
} from './lots';
import { CITY2_SEED } from './site';

// DOVE BEACH ERA LEDGER (G023 / CITY2.md, 1700s to 2050s).
// Deterministic history for every frontage lot and landmark plot in Dove Beach, grounded in
// CITY2.md's chronology. Nothing exceeds 4 storeys except Head Light. Calling `standingIn(year)`
// returns every standing building and active landmark with its lot, kind, role, and era.

export type DoveBeachEra =
  | 'hamlet'
  | 'fort_harbour'
  | 'mills_rail'
  | 'resort'
  | 'interwar_war'
  | 'car_boom'
  | 'decline'
  | 'revival'
  | 'modern';

export type DoveBeachRole =
  | 'fish_shack'
  | 'warehouse'
  | 'fish_market'
  | 'chandler'
  | 'harbour_tavern'
  | 'seafood_restaurant'
  | 'pastel_townhouse'
  | 'shophouse'
  | 'corner_store'
  | 'boarding_house'
  | 'cottage'
  | 'rice_mill'
  | 'timber_mill'
  | 'mill_warehouse'
  | 'shuttered_mill'
  | 'mill_lofts'
  | 'taproom'
  | 'artist_studio'
  | 'ice_cream_parlour'
  | 'surf_shop'
  | 'tackle_shop'
  | 'seafood_shack'
  | 'motor_court'
  | 'boutique_motel'
  | 'beach_hotel'
  | 'boardwalk_cafe'
  | 'raised_cottage'
  | 'beach_bungalow'
  | 'guest_house'
  | 'corner_market'
  | 'postwar_cottage'
  | 'bait_shop'
  | 'ranch_house'
  | 'hillside_villa'
  | 'summer_estate'
  | 'carriage_house'
  | 'ridge_villa'
  | 'ridge_estate'
  | 'overlook_house'
  | 'shrimp_packing'
  | 'boatyard'
  | 'marina_office'
  | 'fish_camp'
  | 'boathouse'
  | 'waterfront_cottage'
  | 'keepers_cottage'
  | 'fort_quarters'
  | 'naval_barracks'
  | 'naval_depot'
  | 'coastal_cottage'
  | 'upland_house'
  | 'upland_school'
  | 'shopping_strip'
  | 'landmark';

export interface BuildingStage {
  /** Inclusive year this building or adaptive reuse begins standing. */
  from: number;
  /** Exclusive year it is demolished or converted (undefined = still standing through 2059). */
  to?: number;
  kind: LotKind;
  role: DoveBeachRole;
  era: DoveBeachEra;
  /** Storeys (strictly 1..4 for every regular building per CITY2.md). */
  storeys: 1 | 2 | 3 | 4;
  note?: string;
}

export interface LotLedgerEntry {
  lotId: string;
  districtId: DoveBeachDistrictId;
  stages: BuildingStage[];
}

export interface LandmarkStage {
  from: number;
  to?: number;
  era: DoveBeachEra;
  storeys: number;
  state: string;
}

export interface LandmarkLedgerEntry {
  landmarkId: string;
  name: string;
  districtId: DoveBeachDistrictId;
  stages: LandmarkStage[];
}

export interface StandingBuilding {
  id: string;
  lotId: string;
  districtId: DoveBeachDistrictId;
  roadId: string;
  roadName: string;
  kind: LotKind;
  role: DoveBeachRole;
  era: DoveBeachEra;
  storeys: 1 | 2 | 3 | 4;
  builtYear: number;
  demolishedYear?: number;
  center: XZ;
  frontPoint: XZ;
  w: number;
  d: number;
  padW: number;
  padD: number;
  heading: number;
  facingAngle: number;
  y: number;
  corners: [XZ, XZ, XZ, XZ];
  padCorners: [XZ, XZ, XZ, XZ];
  note?: string;
}

export interface StandingLandmark {
  id: string;
  name: string;
  districtId: DoveBeachDistrictId;
  era: DoveBeachEra;
  from: number;
  to?: number;
  storeys: number;
  state: string;
  at: XZ;
  w: number;
  d: number;
  heading: number;
  y: number;
  corners: [XZ, XZ, XZ, XZ];
  footprintKind: LandmarkPlot['footprintKind'];
}

export interface StandingSnapshot {
  year: number;
  era: DoveBeachEra;
  buildings: StandingBuilding[];
  landmarks: StandingLandmark[];
  districtCounts: Record<DoveBeachDistrictId, number>;
}

export interface DoveBeachPlan {
  seed: string;
  layout: City2LotLayout;
  lots: City2Lot[];
  landmarkPlots: LandmarkPlot[];
  lotLedger: LotLedgerEntry[];
  landmarkLedger: LandmarkLedgerEntry[];
  standingIn(year: number): StandingSnapshot;
}

export function eraForYear(year: number): DoveBeachEra {
  if (year < 1790) return 'hamlet';
  if (year < 1860) return 'fort_harbour';
  if (year < 1905) return 'mills_rail';
  if (year < 1930) return 'resort';
  if (year < 1950) return 'interwar_war';
  if (year < 1970) return 'car_boom';
  if (year < 1990) return 'decline';
  if (year < 2020) return 'revival';
  return 'modern';
}

function pick<T>(arr: readonly T[], r: number): T {
  return arr[Math.min(arr.length - 1, Math.floor(r * arr.length))]!;
}

function buildLotStages(seed: string, lot: City2Lot, districtOrdinal: number): BuildingStage[] {
  const R = (...k: (string | number)[]) => rand(seed, 'ledger', lot.id, ...k);
  const r0 = R('t0');
  const r1 = R('r1');
  const r2 = R('r2');
  const r3 = R('r3');
  const minYear = lot.roadBuiltYear;

  switch (lot.districtId) {
    case 'harbour': {
      // Earliest hamlet on the Sallow's mouth (1715-1785), expanding through Custom House era (1790-1858).
      const nearMouth = lot.center[0] < 80;
      const from = nearMouth && r0 < 0.45
        ? Math.floor(1720 + r1 * 65)
        : Math.max(1780, Math.floor(1785 + r1 * 75));
      const earlyRole: DoveBeachRole =
        from < 1790
          ? 'fish_shack'
          : pick(['fish_shack', 'warehouse', 'chandler', 'harbour_tavern', 'fish_market'] as const, r2);
      const storeys: 1 | 2 | 3 | 4 = earlyRole === 'fish_shack' ? 1 : r3 < 0.55 ? 2 : 3;
      const stages: BuildingStage[] = [
        { from, kind: lot.kind, role: earlyRole, era: eraForYear(from), storeys },
      ];
      // Some warehouses & fish shacks become seafood restaurants in the 1990s revival.
      if ((earlyRole === 'warehouse' || earlyRole === 'fish_shack') && r3 > 0.55) {
        const conv = Math.floor(1992 + R('conv') * 14);
        stages[0]!.to = conv;
        stages.push({
          from: conv,
          kind: 'shop',
          role: 'seafood_restaurant',
          era: eraForYear(conv),
          storeys: Math.min(2, storeys) as 1 | 2,
          note: 'converted harbourfront seafood spot',
        });
      }
      return stages;
    }

    case 'oldtown': {
      // Old Town climbs Steep Street, Crown Lane & Neck Hill from 1800 through 1895, with later infill.
      const base = Math.max(1800, minYear);
      const from = r0 < 0.72
        ? Math.floor(base + r1 * 65)
        : Math.floor(1865 + r1 * 55);
      const role: DoveBeachRole =
        lot.kind === 'shop'
          ? pick(['shophouse', 'corner_store'] as const, r2)
          : lot.kind === 'apartment'
            ? 'boarding_house'
            : pick(['pastel_townhouse', 'cottage', 'pastel_townhouse'] as const, r2);
      const storeys: 1 | 2 | 3 | 4 =
        lot.kind === 'apartment' ? 3 : lot.kind === 'shop' ? 2 : r3 < 0.5 ? 2 : 1;
      return [{
        from,
        kind: lot.kind,
        role,
        era: eraForYear(from),
        storeys,
        note: 'pastel facade with roof dovecote',
      }];
    }

    case 'mills': {
      // Rice & timber mills arrive 1861-1888, shutter in 1974-1980, revive as lofts/taproom/studios in 1996-2002!
      const from = Math.max(1861, Math.floor(1861 + r0 * 27));
      const closeYear = Math.floor(1973 + r1 * 7);
      const reviveYear = Math.floor(1996 + r2 * 8);
      const origRole: DoveBeachRole = pick(['rice_mill', 'timber_mill', 'mill_warehouse'] as const, r1);
      const revivedRole: DoveBeachRole = pick(['mill_lofts', 'taproom', 'artist_studio'] as const, r3);
      const storeys: 2 | 3 | 4 = origRole === 'mill_warehouse' ? 2 : r0 < 0.5 ? 3 : 4;
      return [
        { from, to: closeYear, kind: lot.kind, role: origRole, era: eraForYear(from), storeys },
        {
          from: closeYear,
          to: reviveYear,
          kind: lot.kind,
          role: 'shuttered_mill',
          era: 'decline',
          storeys,
          note: 'closed mill building',
        },
        {
          from: reviveYear,
          kind: revivedRole === 'mill_lofts' ? 'apartment' : 'shop',
          role: revivedRole,
          era: eraForYear(reviveYear),
          storeys,
          note: 'adaptive reuse of old gorge mill',
        },
      ];
    }

    case 'strand': {
      // The Esplanade & Strand strip opens 1905: boardwalk shops first, motor courts from 1952, surf/boutique revival 1990s.
      const wave = r0;
      if (wave < 0.48) {
        const from = Math.floor(1906 + r1 * 22);
        const role: DoveBeachRole =
          lot.kind === 'apartment'
            ? 'beach_hotel'
            : pick(['ice_cream_parlour', 'tackle_shop', 'seafood_shack', 'boardwalk_cafe'] as const, r2);
        const storeys: 1 | 2 | 3 | 4 = role === 'beach_hotel' ? (r3 < 0.5 ? 3 : 4) : r3 < 0.7 ? 1 : 2;
        const stages: BuildingStage[] = [{ from, kind: lot.kind, role, era: eraForYear(from), storeys }];
        if (role === 'tackle_shop' && r3 > 0.6) {
          const surfYr = Math.floor(1993 + R('surf') * 12);
          stages[0]!.to = surfYr;
          stages.push({ from: surfYr, kind: 'shop', role: 'surf_shop', era: eraForYear(surfYr), storeys: 1 });
        }
        return stages;
      }
      if (wave < 0.82) {
        // 1952+ car-boom motor courts & surf/seafood strip
        const from = Math.floor(1952 + r1 * 16);
        const isMotel = lot.kind === 'apartment' || r2 < 0.45;
        if (isMotel) {
          const reno = Math.floor(1994 + r3 * 16);
          return [
            { from, to: reno, kind: 'apartment', role: 'motor_court', era: eraForYear(from), storeys: 2 },
            {
              from: reno,
              kind: 'apartment',
              role: 'boutique_motel',
              era: eraForYear(reno),
              storeys: 2,
              note: 'renovated mid-century motor court',
            },
          ];
        }
        return [{
          from,
          kind: 'shop',
          role: pick(['seafood_shack', 'ice_cream_parlour', 'surf_shop', 'tackle_shop'] as const, r2),
          era: eraForYear(from),
          storeys: 1,
        }];
      }
      // 1990s-2010s revival infill
      const from = Math.floor(1992 + r1 * 24);
      return [{
        from,
        kind: lot.kind,
        role: pick(['surf_shop', 'boardwalk_cafe', 'boutique_motel'] as const, r2),
        era: eraForYear(from),
        storeys: lot.kind === 'apartment' ? 3 : 2,
      }];
    }

    case 'newtown': {
      // Seagrove 1905 grid: rolls out from nearer the dunes/Esplanade inland over 1905-1965, plus late infill.
      // Distance south-east toward the Esplanade/bay guides earlier vs later build years!
      const beachProximity = Math.max(0, Math.min(1, (lot.center[1] + 300) / 1700));
      const baseFrom = 1905 + Math.floor((1 - beachProximity) * 32 + r0 * 52);
      const from = Math.min(2038, Math.max(1905, baseFrom));
      const role: DoveBeachRole =
        r2 < 0.68
          ? 'raised_cottage'
          : r2 < 0.88
            ? 'beach_bungalow'
            : r2 < 0.96
              ? 'guest_house'
              : 'corner_market';
      const storeys: 1 | 2 = role === 'guest_house' || r3 > 0.55 ? 2 : 1;
      return [{
        from,
        kind: lot.kind,
        role,
        era: eraForYear(from),
        storeys,
        note: role === 'raised_cottage' ? 'pastel cottage on stilts with tin roof' : undefined,
      }];
    }

    case 'fellside': {
      // Post-war cottages on the Fells' lower slopes from 1948, plus old motor court & bait shop from 1952.
      const from = Math.floor(1948 + r0 * 44);
      const role: DoveBeachRole =
        lot.kind === 'shop'
          ? (from >= 1952 && r1 < 0.45 ? 'motor_court' : 'bait_shop')
          : pick(['postwar_cottage', 'ranch_house', 'postwar_cottage'] as const, r2);
      return [{
        from,
        kind: lot.kind,
        role,
        era: eraForYear(from),
        storeys: role === 'ranch_house' || role === 'bait_shop' ? 1 : r3 < 0.7 ? 1 : 2,
      }];
    }

    case 'crown': {
      // Villas around the switchbacks after Crown Road & Observatory open in 1894.
      const from = Math.max(1894, Math.floor(1895 + r0 * 65));
      const role: DoveBeachRole =
        lot.kind === 'estate'
          ? 'summer_estate'
          : pick(['hillside_villa', 'hillside_villa', 'carriage_house'] as const, r1);
      const storeys: 2 | 3 = role === 'carriage_house' ? 2 : r2 < 0.6 ? 2 : 3;
      return [{ from, kind: lot.kind, role, era: eraForYear(from), storeys }];
    }

    case 'ridge': {
      // Big scenic houses after Ridge Drive opens in 1936.
      const from = Math.max(1936, Math.floor(1937 + r0 * 75));
      const role: DoveBeachRole =
        lot.kind === 'estate'
          ? 'ridge_estate'
          : pick(['ridge_villa', 'overlook_house'] as const, r1);
      return [{ from, kind: lot.kind, role, era: eraForYear(from), storeys: r2 < 0.55 ? 2 : 3 }];
    }

    case 'eastcove': {
      // Marina, shrimp dock, boatyards, and fish camp arrive in 1952 (strictly NO buildings before 1952!).
      const from = Math.floor(1952 + r0 * 52);
      const role: DoveBeachRole =
        lot.kind === 'shop'
          ? pick(['marina_office', 'shrimp_packing', 'boatyard', 'fish_camp'] as const, r1)
          : pick(['boathouse', 'waterfront_cottage', 'fish_camp'] as const, r1);
      return [{ from, kind: lot.kind, role, era: eraForYear(from), storeys: r2 < 0.75 ? 1 : 2 }];
    }

    case 'head': {
      // Head: early fort quarters / lighthouse keepers (1800-1925), wartime naval yard barracks/depots
      // (1939-1984, cleared for headland park by 1994), and a few post-1995 coastal villas outside the park.
      if (r0 < 0.55) {
        // Wartime naval yard structures: strictly 1939-1994 (closed 1984, cleared into park 1994)
        const from = Math.floor(1939 + r1 * 5);
        const to = Math.floor(1986 + r2 * 8);
        return [{
          from,
          to,
          kind: lot.kind,
          role: r3 < 0.55 ? 'naval_barracks' : 'naval_depot',
          era: 'interwar_war',
          storeys: r3 < 0.6 ? 2 : 1,
          note: 'wartime naval yard (cleared when headland became park in 1994)',
        }];
      }
      if (r0 < 0.80) {
        const from = Math.max(1911, Math.floor(1912 + r1 * 24));
        return [{
          from,
          kind: lot.kind,
          role: r2 < 0.5 ? 'keepers_cottage' : 'fort_quarters',
          era: eraForYear(from),
          storeys: r3 < 0.7 ? 1 : 2,
        }];
      }
      const from = Math.floor(1996 + r1 * 30);
      return [{
        from,
        kind: lot.kind,
        role: 'coastal_cottage',
        era: eraForYear(from),
        storeys: 2,
      }];
    }

    case 'uplands': {
      // Uplands: built from 1978 onward (houses, school, small shopping strip).
      const from = districtOrdinal === 0 ? 1978 : Math.floor(1979 + r0 * 22);
      const role: DoveBeachRole =
        lot.kind === 'shop'
          ? (r1 < 0.35 ? 'upland_school' : 'shopping_strip')
          : 'upland_house';
      return [{
        from,
        kind: lot.kind,
        role,
        era: eraForYear(from),
        storeys: role === 'shopping_strip' ? 1 : r2 < 0.6 ? 2 : 1,
      }];
    }

    case 'marsh':
      return [];
  }
}

function buildLandmarkStages(lm: LandmarkPlot): LandmarkStage[] {
  switch (lm.id) {
    case 'fort':
      return [
        { from: 1790, to: 1898, era: 'fort_harbour', storeys: 2, state: 'coastal stone fort' },
        { from: 1898, to: 1960, era: 'mills_rail', storeys: 2, state: 'fort with concrete gun batteries' },
        { from: 1960, era: 'car_boom', storeys: 2, state: 'Neck Fort museum' },
      ];
    case 'lighthouse':
      // The sole structure in Dove Beach taller than 4 storeys (CITY2.md).
      return [{ from: 1851, era: 'fort_harbour', storeys: 8, state: 'black-and-white banded lighthouse & keepers\' cottages' }];
    case 'brewery':
      return [
        { from: 1871, to: 1976, era: 'mills_rail', storeys: 4, state: 'working brick brewery' },
        { from: 1976, to: 1996, era: 'decline', storeys: 4, state: 'closed brick brewery' },
        { from: 1996, era: 'revival', storeys: 4, state: 'brewery lofts and taproom' },
      ];
    case 'tunnel':
      return [
        { from: 1942, to: 1984, era: 'interwar_war', storeys: 1, state: 'wartime naval tunnel (closed to public)' },
        { from: 1984, era: 'decline', storeys: 1, state: 'public road tunnel with dove mural' },
      ];
    case 'drawbridge':
      return [
        { from: 1923, to: 1964, era: 'resort', storeys: 1, state: 'Heron Creek swing bridge' },
        { from: 1964, era: 'car_boom', storeys: 1, state: 'Heron Creek bascule drawbridge' },
      ];
    default:
      return [{
        from: lm.from,
        ...(lm.to !== undefined ? { to: lm.to } : {}),
        era: eraForYear(lm.from),
        storeys: lm.id === 'observatory' || lm.id === 'customhouse' || lm.id === 'station' ? 3 : 2,
        state: lm.task,
      }];
  }
}

function emptyDistrictCounts(): Record<DoveBeachDistrictId, number> {
  return {
    harbour: 0, oldtown: 0, mills: 0, newtown: 0, strand: 0, fellside: 0,
    crown: 0, ridge: 0, eastcove: 0, head: 0, marsh: 0, uplands: 0,
  };
}

/**
 * Build the complete deterministic Dove Beach town plan (roads, lots, landmark plots, and 1700s–2050s era ledger).
 */
export function buildDoveBeachPlan(seed = CITY2_SEED): DoveBeachPlan {
  const layout = generateLots(seed);
  const lotById = new Map(layout.lots.map((l) => [l.id, l]));
  const landmarkById = new Map(layout.landmarkPlots.map((l) => [l.id, l]));

  const districtOrdinals = new Map<DoveBeachDistrictId, number>();
  const lotLedger: LotLedgerEntry[] = layout.lots.map((lot) => {
    const ord = districtOrdinals.get(lot.districtId) ?? 0;
    districtOrdinals.set(lot.districtId, ord + 1);
    return {
      lotId: lot.id,
      districtId: lot.districtId,
      stages: buildLotStages(seed, lot, ord),
    };
  });

  const landmarkLedger: LandmarkLedgerEntry[] = layout.landmarkPlots.map((lm) => ({
    landmarkId: lm.id,
    name: lm.name,
    districtId: lm.districtId,
    stages: buildLandmarkStages(lm),
  }));

  function standingIn(year: number): StandingSnapshot {
    const era = eraForYear(year);
    const buildings: StandingBuilding[] = [];
    const districtCounts = emptyDistrictCounts();

    for (let i = 0; i < lotLedger.length; i++) {
      const entry = lotLedger[i]!;
      const stage = entry.stages.find(
        (st) => st.from <= year && (st.to === undefined || year < st.to),
      );
      if (!stage) continue;
      const lot = lotById.get(entry.lotId)!;
      districtCounts[lot.districtId]++;
      buildings.push({
        id: `${lot.id}@${stage.from}`,
        lotId: lot.id,
        districtId: lot.districtId,
        roadId: lot.roadId,
        roadName: lot.roadName,
        kind: stage.kind,
        role: stage.role,
        era: stage.era,
        storeys: stage.storeys,
        builtYear: stage.from,
        ...(stage.to !== undefined ? { demolishedYear: stage.to } : {}),
        center: lot.center,
        frontPoint: lot.frontPoint,
        w: lot.w,
        d: lot.d,
        padW: lot.padW,
        padD: lot.padD,
        heading: lot.heading,
        facingAngle: lot.facingAngle,
        y: lot.y,
        corners: lot.corners,
        padCorners: lot.padCorners,
        ...(stage.note ? { note: stage.note } : {}),
      });
    }

    const landmarks: StandingLandmark[] = [];
    for (let i = 0; i < landmarkLedger.length; i++) {
      const entry = landmarkLedger[i]!;
      const stage = entry.stages.find(
        (st) => st.from <= year && (st.to === undefined || year < st.to),
      );
      if (!stage) continue;
      const lm = landmarkById.get(entry.landmarkId)!;
      landmarks.push({
        id: lm.id,
        name: lm.name,
        districtId: lm.districtId,
        era: stage.era,
        from: stage.from,
        ...(stage.to !== undefined ? { to: stage.to } : {}),
        storeys: stage.storeys,
        state: stage.state,
        at: lm.at,
        w: lm.w,
        d: lm.d,
        heading: lm.heading,
        y: lm.y,
        corners: lm.corners,
        footprintKind: lm.footprintKind,
      });
    }

    return { year, era, buildings, landmarks, districtCounts };
  }

  return {
    seed,
    layout,
    lots: layout.lots,
    landmarkPlots: layout.landmarkPlots,
    lotLedger,
    landmarkLedger,
    standingIn,
  };
}

let cachedDefaultPlan: DoveBeachPlan | null = null;

/** Convenience helper matching G023's `standingIn(year)` entry point (caches default `CITY2_SEED` plan). */
export function standingIn(year: number, plan?: DoveBeachPlan): StandingSnapshot {
  const p = plan ?? (cachedDefaultPlan ??= buildDoveBeachPlan(CITY2_SEED));
  return p.standingIn(year);
}
