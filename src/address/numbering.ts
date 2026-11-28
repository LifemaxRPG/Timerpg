import { rand } from '../sim/rng';
import { cinemaName } from '../town/cinema';
import { LANDMARKS as CITY2_LANDMARKS, type Landmark as City2Landmark } from '../town/city2/site';
import { roadOnFace, type Face, type Reserved } from '../town/districts';
import type { FarmPlan } from '../town/farms';
import { GLB_LANDMARKS, type GlbLandmarkKind } from '../town/glbLandmarks';
import { apartmentCapacity } from '../town/housing';
import type { Rect } from '../town/kinds';
import type { TownPlan } from '../town/plan';
import { PLATTED, type District } from '../town/plat';
import { isStoreSite, storeState, townStoreHistories } from '../town/stores';
import { doveBeachDistrictAt } from './streets';
import type { BuildingEntry, EraBuildingLabel, Parity, StreetRecord, UnitSpec } from './types';

const DISTRICT_DISPLAY: Record<string, string> = {
  core: 'Downtown',
  square: 'Town Square',
  civic: 'Civic Center',
  old: 'Old Town',
  westmain: 'West Main',
  worker: 'Southside',
  rail: 'Railroad District',
  strip: 'Highway Strip',
  postwar: 'Midtown',
  heights: 'The Heights',
  late: 'Outer Subdivisions',
  edge: 'East Edge',
  river: 'Riverfront',
  grounds: 'The Grounds',
  farms: 'Rural Route',
};

const PLACEHOLDER_USES = new Set(['field', 'woods', 'reserved', 'square', 'shops', 'house', 'plaza']);

export interface Projection {
  along: number;
  sideOffset: number;
  distance: number;
  closest: { x: number; y: number };
}

/** Project point (px, py) onto a straight or polyline StreetRecord. */
export function projectOntoStreet(street: StreetRecord, px: number, py: number): Projection {
  if (street.polyline && street.polyline.length >= 2) {
    let bestD = Infinity;
    let bestAlong = 0;
    let bestOff = 0;
    let bestClosest = { x: street.polyline[0]![0], y: street.polyline[0]![1] };

    for (let i = 0; i + 1 < street.polyline.length; i++) {
      const a = street.polyline[i]!;
      const b = street.polyline[i + 1]!;
      const dx = b[0] - a[0];
      const dy = b[1] - a[1];
      const segLen = Math.hypot(dx, dy);
      if (segLen < 1e-6) continue;
      const t = Math.max(0, Math.min(segLen, ((px - a[0]) * dx + (py - a[1]) * dy) / segLen));
      const qx = a[0] + (dx / segLen) * t;
      const qy = a[1] + (dy / segLen) * t;
      const d = Math.hypot(px - qx, py - qy);
      // Left of travel (dx, dy) in +x east / +y south is (dy, -dx)
      const off = ((px - qx) * dy - (py - qy) * dx) / segLen;
      if (d < bestD - 1e-9) {
        bestD = d;
        bestAlong = a[2] + t;
        bestOff = off;
        bestClosest = { x: qx, y: qy };
      }
    }
    return { along: bestAlong, sideOffset: bestOff, distance: bestD, closest: bestClosest };
  }

  const vx = px - street.origin.x;
  const vy = py - street.origin.y;
  const rawAlong = vx * street.dirVec.x + vy * street.dirVec.y;
  const clampedAlong = Math.max(0, Math.min(street.length, rawAlong));
  const qx = street.origin.x + street.dirVec.x * clampedAlong;
  const qy = street.origin.y + street.dirVec.y * clampedAlong;
  const distance = Math.hypot(px - qx, py - qy);
  // Left of travel (ux, uy) in +x east / +y south is (uy, -ux)
  const sideOffset = vx * street.dirVec.y - vy * street.dirVec.x;
  return { along: clampedAlong, sideOffset, distance, closest: { x: qx, y: qy } };
}

/** Convert (street, along, sideOffset) into a coordinate-based house number and parity. */
export function coordinateHouseNumber(
  street: StreetRecord,
  along: number,
  sideOffset: number,
): { number: number; parity: Parity } {
  const parity: Parity = sideOffset > 0 ? 'odd' : 'even';
  const blocks = street.blocks;
  if (!blocks.length) {
    const base = 100 + Math.floor(Math.max(0, along) * 2) * 2;
    return { number: base + (parity === 'odd' ? 1 : 0), parity };
  }
  const d = Math.max(0, Math.min(street.length, along));
  let bi = 0;
  for (let i = 0; i < blocks.length; i++) {
    if (blocks[i]!.along <= d + 1e-6) bi = i;
    else break;
  }
  const bStart = blocks[bi]!.along;
  const bEnd = bi + 1 < blocks.length ? blocks[bi + 1]!.along : Math.max(street.length, bStart + 10);
  const blockNum = blocks[bi]!.blockNumber;
  const frac = Math.max(0, Math.min(0.999, (d - bStart) / Math.max(1, bEnd - bStart)));
  // Slots 0..45 -> even 0..90, odd 1..91 (leaves 92..99 headroom within the 100-block)
  const slot = Math.floor(frac * 46);
  const number = blockNum + slot * 2 + (parity === 'odd' ? 1 : 0);
  return { number, parity };
}

export function formatHouseNumber(num: number, suffix?: string): string {
  if (!suffix) return String(num);
  if (suffix === '1/2') return `${num} 1/2`;
  return `${num}${suffix}`;
}

interface PendingBuilding {
  id: string;
  aliases: string[];
  kind: BuildingEntry['kind'];
  label: string;
  roles: string[];
  district: string;
  districtName: string;
  streetId: string;
  along: number;
  sideOffset: number;
  position: { x: number; y: number };
  fromYear: number;
  toYear?: number;
  units: UnitSpec[];
  eraLabels?: EraBuildingLabel[];
  preferHalf?: boolean;
}

function entranceOfRect(r: Rect, face: Face): { x: number; y: number } {
  const inset = Math.min(1.5, Math.min(r.w, r.h) / 4);
  if (face === 'N') return { x: r.x + r.w / 2, y: r.y + inset };
  if (face === 'S') return { x: r.x + r.w / 2, y: r.y + r.h - inset };
  if (face === 'W') return { x: r.x + inset, y: r.y + r.h / 2 };
  return { x: r.x + r.w - inset, y: r.y + r.h / 2 };
}

function attachRectToStreet(
  plan: TownPlan,
  streets: readonly StreetRecord[],
  rect: Rect,
  preferredFace?: Face,
): { street: StreetRecord; proj: Projection; pos: { x: number; y: number } } {
  // Build map roadIdx -> StreetRecord[] (up to 2 when split across town origin)
  const byRoad = (roadIdx: number): StreetRecord[] => streets.filter((s) => s.roadIndices.includes(roadIdx));

  const tryFace = (f: Face): { street: StreetRecord; proj: Projection; pos: { x: number; y: number } } | null => {
    const rIdx = roadOnFace(plan.roads, rect, f);
    if (rIdx < 0) return null;
    const cands = byRoad(rIdx);
    if (!cands.length) return null;
    const pos = entranceOfRect(rect, f);
    let bestStreet = cands[0]!;
    let bestProj = projectOntoStreet(bestStreet, pos.x, pos.y);
    for (let i = 1; i < cands.length; i++) {
      const s = cands[i]!;
      const p = projectOntoStreet(s, pos.x, pos.y);
      if (p.distance < bestProj.distance - 1e-6) {
        bestStreet = s;
        bestProj = p;
      }
    }
    return { street: bestStreet, proj: bestProj, pos };
  };

  if (preferredFace) {
    const hit = tryFace(preferredFace);
    if (hit) return hit;
  }

  // Try all four faces; prefer main street, then the face matching lot orientation (N/S for portrait/square, E/W for landscape)
  const faceOrder: Face[] = rect.w > rect.h ? ['E', 'W', 'S', 'N'] : ['S', 'N', 'E', 'W'];
  const matches: { street: StreetRecord; proj: Projection; pos: { x: number; y: number }; main: boolean }[] = [];
  for (const f of faceOrder) {
    const hit = tryFace(f);
    if (hit) matches.push({ ...hit, main: hit.street.kind === 'main' });
  }
  if (matches.length) {
    matches.sort((a, b) => Number(b.main) - Number(a.main) || a.proj.distance - b.proj.distance);
    const chosen = matches[0]!;
    return { street: chosen.street, proj: chosen.proj, pos: chosen.pos };
  }

  // Fallback: nearest non-country street (e.g. depot inside railBand or v1 landmark slightly set back)
  const cx = rect.x + rect.w / 2;
  const cy = rect.y + rect.h / 2;
  const townStreets = streets.filter((s) => s.kind !== 'country' && s.kind !== 'ring');
  const pool = townStreets.length ? townStreets : streets;
  let bestStreet = pool[0]!;
  let bestProj = projectOntoStreet(bestStreet, cx, cy);
  for (let i = 1; i < pool.length; i++) {
    const s = pool[i]!;
    const p = projectOntoStreet(s, cx, cy);
    if (p.distance < bestProj.distance - 1e-6) {
      bestStreet = s;
      bestProj = p;
    }
  }
  return { street: bestStreet, proj: bestProj, pos: { x: cx, y: cy } };
}

function buildApartmentUnits(seed: string, lotId: string): UnitSpec[] {
  const look = rand(seed, 'look', lotId);
  const cap = apartmentCapacity(look, 2025);
  const units: UnitSpec[] = [];
  if (cap >= 6) {
    for (let floor = 1; floor <= 3; floor++) {
      for (let u = 1; u <= 2; u++) {
        units.push({ label: `Apt ${floor}0${u}`, kind: 'apartment', floor });
      }
    }
  } else if (cap === 4) {
    for (let floor = 1; floor <= 2; floor++) {
      for (const letter of ['A', 'B']) {
        units.push({ label: `Apt ${floor}${letter}`, kind: 'apartment', floor });
      }
    }
  } else {
    for (let i = 0; i < Math.max(2, cap); i++) {
      units.push({ label: `Apt ${String.fromCharCode(65 + i)}`, kind: 'apartment', floor: 1 });
    }
  }
  return units;
}

function buildShopUnits(seed: string, lotId: string, district: string): UnitSpec[] {
  // Core downtown storefronts often have an upstairs flat or rear office ("1/2" address)
  if (district === 'core' && rand(seed, 'shop-upstairs', lotId) < 0.35) {
    return [{ label: '1/2', kind: 'half', floor: 2 }];
  }
  return [];
}

function rolesFromGlbRole(role: string, name: string): string[] {
  const out = new Set<string>([role.replace(/_/g, ' ')]);
  const lower = name.toLowerCase();
  if (role === 'diner' || lower.includes('diner')) out.add('diner');
  if (role === 'carhop') { out.add('diner'); out.add('drive-in'); out.add('restaurant'); }
  if (role === 'gas_station') { out.add('gas'); out.add('service station'); out.add('fuel'); }
  if (role === 'motel' || role === 'lodge') { out.add('motel'); out.add('hotel'); out.add('lodging'); }
  if (role === 'courthouse') { out.add('court'); out.add('county courthouse'); out.add('civic'); }
  if (role === 'post_office') { out.add('mail'); out.add('post'); out.add('postal'); }
  if (role === 'fire_station') { out.add('fire'); out.add('firehouse'); }
  if (role === 'police_station') { out.add('police'); out.add('jail'); out.add('precinct'); }
  if (role === 'school' || role === 'elementary_school') { out.add('school'); }
  if (role === 'library') { out.add('library'); out.add('books'); }
  if (role === 'drugstore') { out.add('pharmacy'); out.add('drugstore'); out.add('soda fountain'); }
  if (role === 'bank') { out.add('bank'); }
  if (role === 'church') { out.add('church'); out.add('chapel'); }
  if (role === 'newspaper') { out.add('newspaper'); out.add('gazette'); out.add('press'); }
  if (role === 'bowling') { out.add('bowling'); out.add('bowling alley'); }
  if (role === 'video_store' || role === 'phone_repair') { out.add('video'); out.add('strip center'); }
  if (role === 'laundromat') { out.add('laundromat'); out.add('laundry'); }
  if (role === 'barber') { out.add('barber'); out.add('barbershop'); }
  if (role === 'salon') { out.add('salon'); out.add('beauty parlor'); }
  if (role === 'pawn_shop') { out.add('pawn'); out.add('pawn shop'); }
  if (role === 'five_and_dime') { out.add('five and dime'); out.add('dime store'); out.add('variety store'); }
  if (role === 'ballpark') { out.add('ballpark'); out.add('stadium'); out.add('baseball'); }
  if (role === 'grain_elevator') { out.add('elevator'); out.add('grain'); }
  if (role === 'railroad_depot') { out.add('depot'); out.add('train station'); out.add('station'); }
  return [...out];
}

function glbUnits(plotId: string): UnitSpec[] {
  if (plotId === 'glb:reel_time_video') {
    return [
      { label: 'Suite A', kind: 'suite', floor: 1 },
      { label: 'Suite B', kind: 'suite', floor: 1 },
      { label: 'Suite C', kind: 'suite', floor: 1 },
    ];
  }
  if (plotId === 'glb:gazette_office' || plotId === 'glb:small_town_bank') {
    return [
      { label: 'Suite 100', kind: 'suite', floor: 1 },
      { label: 'Suite 200', kind: 'suite', floor: 2 },
    ];
  }
  if (plotId === 'glb:five_and_dime' || plotId === 'glb:corner_drugstore') {
    return [{ label: '1/2', kind: 'half', floor: 2 }];
  }
  return [];
}

function reservedInfo(
  plan: TownPlan,
  q: Reserved,
  storeHistories: ReturnType<typeof townStoreHistories>,
): {
  skip: boolean;
  kind: BuildingEntry['kind'];
  label: string;
  roles: string[];
  aliases: string[];
  fromYear: number;
  toYear?: number;
  units: UnitSpec[];
  eraLabels?: EraBuildingLabel[];
} {
  if (q.id.startsWith('future:')) {
    return { skip: true, kind: 'civic', label: '', roles: [], aliases: [], fromYear: 9999, units: [] };
  }

  if (q.id.startsWith('landmark:')) {
    const lmId = q.id.slice('landmark:'.length);
    const lm = plan.landmarks.find((l) => l.id === lmId);
    const openYear = lm?.openYear ?? q.chain.find((u) => !PLACEHOLDER_USES.has(u.use))?.from ?? 1890;
    const label = lm?.name ?? lmId;
    const roles: string[] = [lmId];
    const eraLabels: EraBuildingLabel[] = [];

    if (lmId === 'cinema') {
      roles.push('cinema', 'theater', 'theatre', 'movies');
      for (const [from, to] of [[openYear, 1955], [1955, 1978], [1978, 1995], [1995, undefined]] as const) {
        if (to !== undefined && to <= openYear) continue;
        const start = Math.max(openYear, from);
        eraLabels.push({ from: start, ...(to !== undefined ? { to } : {}), label: cinemaName(plan.seed, start), role: 'cinema' });
      }
    } else if (isStoreSite(lmId)) {
      roles.push('store', 'grocery', 'market');
      if (lmId === 'generalstore') roles.push('general store');
      if (lmId === 'supermarket') roles.push('supermarket');
      if (lmId === 'superstore') roles.push('superstore', 'department store');
      const h = storeHistories.find((x) => x.site === lmId);
      if (h) {
        // Record distinct sign eras from the store's events
        const years = [...new Set(h.events.map((e) => e.year))].sort((a, b) => a - b);
        for (let i = 0; i < years.length; i++) {
          const y = years[i]!;
          const st = storeState(plan.seed, h, y);
          if (!st) continue;
          const nextY = years[i + 1];
          eraLabels.push({ from: y, ...(nextY !== undefined ? { to: nextY } : {}), label: st.sign, role: st.reuse ?? st.variant });
        }
      }
    } else if (lmId === 'hotel') {
      roles.push('hotel', 'lodging', 'inn');
    } else if (lmId === 'clocktower') {
      roles.push('clock tower', 'clocktower', 'civic');
    } else if (lmId === 'post') {
      roles.push('post office', 'mail', 'civic');
    } else if (lmId === 'fire') {
      roles.push('fire station', 'firehouse', 'civic');
    } else if (lmId === 'police') {
      roles.push('police station', 'police', 'jail', 'civic');
    } else if (lmId === 'school') {
      roles.push('school', 'civic');
    } else if (lmId === 'hospital') {
      roles.push('hospital', 'clinic', 'medical', 'civic');
    }

    const units: UnitSpec[] =
      lmId === 'hotel'
        ? [
            { label: 'Suite 101', kind: 'suite', floor: 1 },
            { label: 'Room 201', kind: 'unit', floor: 2 },
            { label: 'Room 202', kind: 'unit', floor: 2 },
          ]
        : [];

    return {
      skip: false,
      kind: 'landmark',
      label,
      roles,
      aliases: [lmId],
      fromYear: openYear,
      units,
      ...(eraLabels.length ? { eraLabels } : {}),
    };
  }

  if (q.id.startsWith('glb:')) {
    const activeUses = q.chain.filter((u) => !PLACEHOLDER_USES.has(u.use));
    if (!activeUses.length) {
      return { skip: true, kind: 'glb', label: '', roles: [], aliases: [], fromYear: 9999, units: [] };
    }
    const fromYear = activeUses[0]!.from;
    const lastUse = activeUses[activeUses.length - 1]!;
    const toYear = lastUse.to;
    const roles = new Set<string>();
    const eraLabels: EraBuildingLabel[] = [];
    for (const u of activeUses) {
      const spec = GLB_LANDMARKS[u.use as GlbLandmarkKind];
      const name = spec?.name ?? u.use.replace(/^landmark_glb_/, '').replace(/_/g, ' ');
      const role = spec?.role ?? 'landmark';
      for (const r of rolesFromGlbRole(role, name)) roles.add(r);
      eraLabels.push({ from: u.from, ...(u.to !== undefined ? { to: u.to } : {}), label: name, role });
    }
    const shortAlias = q.id.slice('glb:'.length);
    return {
      skip: false,
      kind: 'glb',
      label: eraLabels[0]!.label,
      roles: [...roles],
      aliases: [shortAlias],
      fromYear,
      ...(toYear !== undefined ? { toYear } : {}),
      units: glbUnits(q.id),
      eraLabels,
    };
  }

  if (q.id === 'civic:plaza') {
    return {
      skip: false,
      kind: 'civic',
      label: 'Town Hall & Square',
      roles: ['town hall', 'city hall', 'plaza', 'square', 'civic'],
      aliases: ['townhall', 'plaza', 'civic:townhall'],
      fromYear: PLATTED.square,
      units: [
        { label: 'Room 101', kind: 'suite', floor: 1 },
        { label: 'Council Chambers', kind: 'suite', floor: 2 },
      ],
    };
  }
  if (q.id === 'civic:townhall') {
    return {
      skip: false,
      kind: 'civic',
      label: 'Town Hall',
      roles: ['town hall', 'city hall', 'civic'],
      aliases: ['townhall'],
      fromYear: q.chain[0]?.from ?? 1885,
      units: [],
    };
  }
  if (q.id === 'civic:depot') {
    return {
      skip: false,
      kind: 'civic',
      label: 'Railroad Depot',
      roles: ['depot', 'train station', 'station', 'railroad'],
      aliases: ['depot'],
      fromYear: q.chain[0]?.from ?? 1888,
      units: [],
    };
  }
  if (q.id === 'civic:chapel') {
    return {
      skip: false,
      kind: 'civic',
      label: 'Old Town Chapel',
      roles: ['chapel', 'church'],
      aliases: ['chapel'],
      fromYear: q.chain[0]?.from ?? 1870,
      units: [],
    };
  }
  if (q.id === 'civic:cemetery') {
    return {
      skip: false,
      kind: 'civic',
      label: 'Town Cemetery',
      roles: ['cemetery', 'graveyard', 'burial ground'],
      aliases: ['cemetery'],
      fromYear: q.chain[0]?.from ?? 1885,
      units: [],
    };
  }

  return {
    skip: false,
    kind: 'civic',
    label: q.id,
    roles: ['civic'],
    aliases: [],
    fromYear: q.chain[0]?.from ?? 1885,
    units: [],
  };
}

function overlapsTime(a: { fromYear: number; toYear?: number }, b: { fromYear: number; toYear?: number }): boolean {
  const aEnd = a.toYear ?? Infinity;
  const bEnd = b.toYear ?? Infinity;
  return a.fromYear < bEnd && b.fromYear < aEnd;
}

/** Assign permanent, monotonic, odd/even house numbers (with gaps, 1/2, and unit letters) to all pending buildings. */
export function assignNumbers(streets: readonly StreetRecord[], pending: readonly PendingBuilding[]): BuildingEntry[] {
  const streetById = new Map(streets.map((s) => [s.id, s]));
  const groups = new Map<string, PendingBuilding[]>();

  for (const p of pending) {
    const parity: Parity = p.sideOffset > 0 ? 'odd' : 'even';
    const key = `${p.streetId}|${parity}`;
    const arr = groups.get(key) ?? [];
    arr.push(p);
    groups.set(key, arr);
  }

  const out: BuildingEntry[] = [];

  for (const [key, items] of groups) {
    const [streetId, parityStr] = key.split('|') as [string, Parity];
    const street = streetById.get(streetId)!;

    // Sort strictly by position along the street (then fromYear, then id) so numbers always rise away from origin
    items.sort((a, b) => a.along - b.along || a.fromYear - b.fromYear || (a.id < b.id ? -1 : 1));

    const assigned: BuildingEntry[] = [];

    for (const item of items) {
      const coord = coordinateHouseNumber(street, item.along, item.sideOffset);
      let num = coord.number;
      let suffix: string | undefined = item.preferHalf ? '1/2' : undefined;

      // Ensure monotonic ordering and zero collisions against all prior buildings overlapping in time
      const overlappingPrior = assigned.filter((prev) => overlapsTime(prev, item));
      if (overlappingPrior.length) {
        const last = overlappingPrior[overlappingPrior.length - 1]!;
        const sameSpot = Math.abs(item.along - last.along) <= 0.75;
        if (sameSpot && num <= last.number) {
          // Co-located or split parcel: keep same base number and disambiguate with "1/2" or unit letters A, B, C...
          num = last.number;
          const usedSuffixes = new Set(
            overlappingPrior.filter((p) => p.number === num).map((p) => p.numberSuffix ?? ''),
          );
          if (!usedSuffixes.has('1/2')) {
            suffix = '1/2';
          } else {
            for (const letter of ['A', 'B', 'C', 'D', 'E', 'F']) {
              if (!usedSuffixes.has(letter)) {
                suffix = letter;
                break;
              }
            }
          }
        } else if (num <= last.number) {
          // Advance to the next same-parity integer so numbers strictly rise along the street
          num = last.number + 2;
          suffix = undefined;
        }
      }

      // Final uniqueness guard within overlapping active years on this street+parity
      const collides = (n: number, sfx?: string): boolean => {
        const formatted = formatHouseNumber(n, sfx);
        return overlappingPrior.some((p) => p.houseNumber === formatted);
      };
      while (collides(num, suffix)) {
        num += 2;
        suffix = undefined;
      }

      const entry: BuildingEntry = {
        id: item.id,
        aliases: item.aliases,
        kind: item.kind,
        label: item.label,
        roles: item.roles,
        district: item.district,
        districtName: item.districtName,
        streetId: item.streetId,
        number: num,
        ...(suffix ? { numberSuffix: suffix } : {}),
        houseNumber: formatHouseNumber(num, suffix),
        parity: parityStr,
        along: Math.round(item.along * 100) / 100,
        sideOffset: Math.round(item.sideOffset * 100) / 100,
        position: {
          x: Math.round(item.position.x * 100) / 100,
          y: Math.round(item.position.y * 100) / 100,
        },
        fromYear: item.fromYear,
        ...(item.toYear !== undefined ? { toYear: item.toYear } : {}),
        units: item.units,
        ...(item.eraLabels ? { eraLabels: item.eraLabels } : {}),
      };
      assigned.push(entry);
      out.push(entry);
    }
  }

  out.sort((a, b) => (a.streetId < b.streetId ? -1 : a.streetId > b.streetId ? 1 : a.number - b.number || (a.id < b.id ? -1 : 1)));
  return out;
}

/** Number every building in a grid/platted town (candidates, landmarks, reserved GLB/civic plots, and farmsteads). */
export function numberTownBuildings(
  plan: TownPlan,
  farmPlan: FarmPlan | null,
  streets: readonly StreetRecord[],
  foundedYear: number,
): BuildingEntry[] {
  const pending: PendingBuilding[] = [];
  const coveredLandmarks = new Set<string>();
  const storeHist = townStoreHistories(plan.seed, plan.landmarks.map((l) => l.id));

  // 1. Candidate lots (houses, villas, estates, small/medium homes, apartments, shops)
  for (const c of plan.candidates) {
    const attached = attachRectToStreet(plan, streets, c);
    const district = c.district ?? 'old';
    const isApt = c.kind === 'apartment';
    const isShop = c.kind === 'shop';
    const kind: BuildingEntry['kind'] = isShop ? 'shop' : isApt ? 'apartment' : 'house';
    const units = isApt
      ? buildApartmentUnits(plan.seed, c.id)
      : isShop
        ? buildShopUnits(plan.seed, c.id, district)
        : [];
    const label = isShop ? 'Commercial Building' : isApt ? 'Apartment Building' : 'Residence';
    const roles = isShop ? ['shop', 'store', 'commercial'] : isApt ? ['apartment', 'apartments', 'residence'] : ['house', 'home', 'residence'];

    pending.push({
      id: c.id,
      aliases: [],
      kind,
      label,
      roles,
      district,
      districtName: DISTRICT_DISPLAY[district] ?? district,
      streetId: attached.street.id,
      along: attached.proj.along,
      sideOffset: attached.proj.sideOffset,
      position: attached.pos,
      fromYear: c.from !== undefined ? Math.min(c.from, 9999) : foundedYear,
      units,
    });
  }

  // 2. Reserved plots (GLB landmarks, civic plots, v2 landmarks)
  for (const q of plan.v2?.reserved ?? []) {
    const info = reservedInfo(plan, q, storeHist);
    if (info.skip) continue;
    if (q.id.startsWith('landmark:')) coveredLandmarks.add(q.id.slice('landmark:'.length));
    const attached = attachRectToStreet(plan, streets, q, q.face);
    pending.push({
      id: q.id,
      aliases: info.aliases,
      kind: info.kind,
      label: info.label,
      roles: info.roles,
      district: q.district,
      districtName: DISTRICT_DISPLAY[q.district] ?? q.district,
      streetId: attached.street.id,
      along: attached.proj.along,
      sideOffset: attached.proj.sideOffset,
      position: attached.pos,
      fromYear: info.fromYear,
      ...(info.toYear !== undefined ? { toYear: info.toYear } : {}),
      units: info.units,
      ...(info.eraLabels ? { eraLabels: info.eraLabels } : {}),
    });
  }

  // 3. Any plan.landmarks not already covered by plan.v2.reserved (e.g. v1 plans)
  for (const lm of plan.landmarks) {
    if (coveredLandmarks.has(lm.id)) continue;
    const attached = attachRectToStreet(plan, streets, lm);
    pending.push({
      id: `landmark:${lm.id}`,
      aliases: [lm.id],
      kind: 'landmark',
      label: lm.name,
      roles: [lm.id, 'landmark'],
      district: 'core',
      districtName: 'Downtown',
      streetId: attached.street.id,
      along: attached.proj.along,
      sideOffset: attached.proj.sideOffset,
      position: attached.pos,
      fromYear: lm.openYear,
      units: [],
    });
  }

  // 4. Rural farmsteads along the country roads
  if (farmPlan) {
    const streetById = new Map(streets.map((s) => [s.id, s]));
    for (const farm of farmPlan.farms) {
      const street = streetById.get(`country:${farm.road}`);
      if (!street) continue;
      const cx = farm.rect.x + farm.rect.w / 2;
      const cy = farm.rect.y + farm.rect.h / 2;
      const proj = projectOntoStreet(street, cx, cy);
      pending.push({
        id: farm.id,
        aliases: [],
        kind: 'farm',
        label: `${farm.household.surname} Farm`,
        roles: ['farm', 'farmstead', ...(farm.produce ? ['produce stand', 'farm stand'] : [])],
        district: 'farms',
        districtName: 'Rural Route',
        streetId: street.id,
        along: proj.along,
        sideOffset: proj.sideOffset,
        position: { x: cx, y: cy },
        fromYear: farm.founded,
        units: [],
      });
    }
  }

  return assignNumbers(streets, pending);
}

/** Optional external building descriptor for Dove Beach (so callers or future lot generators can address every building). */
export interface DoveBeachBuildingInput {
  id: string;
  aliases?: string[];
  kind?: BuildingEntry['kind'];
  label: string;
  roles?: string[];
  at: [number, number];
  fromYear: number;
  toYear?: number;
  district?: string;
  units?: UnitSpec[];
}

function landmarkRoles(lm: City2Landmark): string[] {
  const roles = new Set<string>([lm.id, 'landmark']);
  const text = `${lm.name} ${lm.task}`.toLowerCase();
  for (const kw of [
    'observatory', 'funicular', 'custom house', 'pier', 'pavilion', 'fountain', 'lighthouse',
    'fort', 'museum', 'viaduct', 'tunnel', 'belvedere', 'overlook', 'radio', 'mast', 'marina',
    'dock', 'station', 'train station', 'brewery', 'taproom', 'drawbridge', 'cottages', 'beach',
  ]) {
    if (text.includes(kw)) roles.add(kw);
  }
  return [...roles];
}

/** Number all Dove Beach landmarks (plus any caller-supplied Dove Beach buildings) onto Dove Beach's curved & grid streets. */
export function numberDoveBeachBuildings(
  streets: readonly StreetRecord[],
  extraBuildings: readonly DoveBeachBuildingInput[] = [],
): BuildingEntry[] {
  const pending: PendingBuilding[] = [];

  const attachNearest = (
    x: number,
    z: number,
    fromYear: number,
    allowTunnel = false,
  ): { street: StreetRecord; proj: Projection } => {
    // Prefer surface streets already built when picking among nearby streets; only tunnel portals use Headland Tunnel
    let bestStreet: StreetRecord | null = null;
    let bestProj: Projection | null = null;
    let bestScore = Infinity;

    for (const s of streets) {
      if (!allowTunnel && s.id === 'city2:tunnel') continue;
      const p = projectOntoStreet(s, x, z);
      const score = p.distance + (s.builtYear > fromYear ? 25 : 0);
      if (score < bestScore - 1e-6) {
        bestStreet = s;
        bestProj = p;
        bestScore = score;
      }
    }
    if (!bestStreet || !bestProj) {
      bestStreet = streets[0]!;
      bestProj = projectOntoStreet(bestStreet, x, z);
    }
    return { street: bestStreet, proj: bestProj };
  };

  for (const lm of CITY2_LANDMARKS) {
    const [x, z] = lm.at;
    const dist = doveBeachDistrictAt(x, z);
    const { street, proj } = attachNearest(x, z, lm.from, lm.id.includes('tunnel') || /tunnel/i.test(lm.name));
    const units: UnitSpec[] =
      lm.id === 'brewery'
        ? [
            { label: 'Taproom', kind: 'suite', floor: 1 },
            { label: 'Loft 2A', kind: 'apartment', floor: 2 },
            { label: 'Loft 2B', kind: 'apartment', floor: 2 },
          ]
        : lm.id === 'customhouse'
          ? [
              { label: 'Harbourmaster Office', kind: 'suite', floor: 1 },
              { label: 'Clock Room', kind: 'suite', floor: 2 },
            ]
          : [];

    pending.push({
      id: lm.id,
      aliases: [`landmark:${lm.id}`],
      kind: 'landmark',
      label: lm.name,
      roles: landmarkRoles(lm),
      district: dist.id,
      districtName: dist.name,
      streetId: street.id,
      along: proj.along,
      sideOffset: proj.sideOffset,
      position: { x, y: z },
      fromYear: lm.from,
      ...(lm.to !== undefined ? { toYear: lm.to } : {}),
      units,
    });
  }

  for (const b of extraBuildings) {
    const [x, z] = b.at;
    const dist = doveBeachDistrictAt(x, z);
    const { street, proj } = attachNearest(x, z, b.fromYear, /tunnel/i.test(b.label));
    pending.push({
      id: b.id,
      aliases: b.aliases ?? [],
      kind: b.kind ?? 'house',
      label: b.label,
      roles: b.roles ?? [b.kind ?? 'building'],
      district: b.district ?? dist.id,
      districtName: dist.name,
      streetId: street.id,
      along: proj.along,
      sideOffset: proj.sideOffset,
      position: { x, y: z },
      fromYear: b.fromYear,
      ...(b.toYear !== undefined ? { toYear: b.toYear } : {}),
      units: b.units ?? [],
    });
  }

  return assignNumbers(streets, pending);
}
