import { describe, expect, it } from 'vitest';
import { roadGround } from './ground';
import {
  buildDoveBeachPlan,
  eraForYear,
  type DoveBeachPlan,
} from './ledger';
import {
  isInMarsh,
  isOnBeachOrDunes,
  MAX_BUILDABLE_GRADE,
  polygonsOverlap,
  SEAGROVE_STREET_WIDTH,
  type XZ,
} from './lots';
import { renderDoveBeachSvg } from './mapSvg';
import {
  CITY2_SEED,
  coastDistance,
  DISTRICTS,
  inside,
  LANDMARKS,
  MARSH,
  RAIL,
  riverAt,
} from './site';

function distPointToSeg(px: number, pz: number, a: XZ, b: XZ): number {
  const dx = b[0] - a[0];
  const dz = b[1] - a[1];
  const len2 = dx * dx + dz * dz;
  if (len2 < 1e-9) return Math.hypot(px - a[0], pz - a[1]);
  const t = Math.max(0, Math.min(1, ((px - a[0]) * dx + (pz - a[1]) * dz) / len2));
  return Math.hypot(px - (a[0] + dx * t), pz - (a[1] + dz * t));
}

function distToPolyline(px: number, pz: number, line: readonly XZ[]): number {
  let best = Infinity;
  for (let i = 0; i < line.length - 1; i++) {
    const d = distPointToSeg(px, pz, line[i]!, line[i + 1]!);
    if (d < best) best = d;
  }
  return best;
}

function samplePointsOfLot(corners: readonly [XZ, XZ, XZ, XZ], center: XZ): XZ[] {
  const pts: XZ[] = [center, corners[0], corners[1], corners[2], corners[3]];
  for (let i = 0; i < 4; i++) {
    const a = corners[i]!;
    const b = corners[(i + 1) % 4]!;
    pts.push([(a[0] + b[0]) * 0.5, (a[1] + b[1]) * 0.5]);
  }
  return pts;
}

const plan: DoveBeachPlan = buildDoveBeachPlan(CITY2_SEED);
const rg = roadGround(plan.layout.roads);

describe('city2 / Dove Beach lots on curved and sloped streets', () => {
  it('generates lots across all 11 buildable districts and keeps Heron Marsh untouched', () => {
    expect(plan.lots.length).toBeGreaterThan(500);

    const byDistrict = new Map<string, number>();
    for (const lot of plan.lots) {
      byDistrict.set(lot.districtId, (byDistrict.get(lot.districtId) ?? 0) + 1);
    }

    for (const d of DISTRICTS) {
      if (d.id === 'marsh') {
        expect(byDistrict.get('marsh') ?? 0).toBe(0);
      } else {
        expect(byDistrict.get(d.id) ?? 0, `district ${d.id} should have lots`).toBeGreaterThan(0);
      }
    }
  });

  it('gives every lot valid front/back yards, pad inside lot, and road frontage', () => {
    const roadMap = new Map(plan.layout.roads.map((r) => [r.spec.id, r]));
    const streetMap = new Map(plan.layout.streets.map((s) => [s.id, s]));

    for (const lot of plan.lots) {
      expect(lot.w).toBeGreaterThan(0);
      expect(lot.d).toBeGreaterThan(0);
      expect(lot.padW).toBeGreaterThan(0);
      expect(lot.padD).toBeGreaterThan(0);
      expect(lot.padW).toBeLessThanOrEqual(lot.w + 1e-6);
      expect(lot.padD).toBeLessThanOrEqual(lot.d + 1e-6);
      expect(lot.frontYard).toBeGreaterThanOrEqual(0);
      expect(lot.backYard).toBeGreaterThanOrEqual(0);
      expect(lot.frontYard + lot.padD + lot.backYard).toBeCloseTo(lot.d, 1);

      const road = roadMap.get(lot.roadId);
      const street = streetMap.get(lot.roadId);
      expect(road ?? street, `host road/street ${lot.roadId} must exist`).toBeDefined();

      if (street) {
        const dCenter = distPointToSeg(lot.frontPoint[0], lot.frontPoint[1], street.a, street.b);
        const halfW = SEAGROVE_STREET_WIDTH * 0.5;
        expect(dCenter).toBeGreaterThanOrEqual(halfW + 0.5);
        expect(dCenter).toBeLessThanOrEqual(halfW + 6.5);
      } else if (road) {
        const hit = rg.road(lot.frontPoint[0], lot.frontPoint[1]);
        expect(hit === null || hit.out > 0, `${lot.id} frontPoint on road`).toBe(true);
        const halfW = road.spec.width * 0.5;
        let best = Infinity;
        for (const s of road.a.samples) {
          const dist = Math.hypot(lot.frontPoint[0] - s.x, lot.frontPoint[1] - s.z);
          if (dist < best) best = dist;
        }
        expect(best).toBeGreaterThanOrEqual(halfW + 0.5);
        expect(best).toBeLessThanOrEqual(halfW + 9.0);
      }
    }
  });

  it('keeps every lot off every carriageway, off steep grades, off beach/dunes, off Heron Marsh, off the river, and off the rail', () => {
    const streets = plan.layout.streets;
    const halfStreet = SEAGROVE_STREET_WIDTH * 0.5;

    for (const lot of plan.lots) {
      expect(lot.grade).toBeLessThanOrEqual(MAX_BUILDABLE_GRADE + 1e-6);
      expect(lot.districtId).not.toBe('marsh');

      const pts = samplePointsOfLot(lot.corners, lot.center);
      for (const [x, z] of pts) {
        const hit = rg.road(x, z);
        expect(hit === null || hit.out > 0, `${lot.id} point on curved road`).toBe(true);
        for (const st of streets) {
          expect(
            distPointToSeg(x, z, st.a, st.b),
            `${lot.id} overlaps Seagrove street ${st.id}`,
          ).toBeGreaterThanOrEqual(halfStreet + 0.4);
        }
        expect(isInMarsh(x, z), `${lot.id} inside Heron Marsh`).toBe(false);
        expect(inside(x, z, MARSH)).toBe(false);
        expect(isOnBeachOrDunes(x, z), `${lot.id} on beach or dunes`).toBe(false);
        expect(coastDistance(x, z), `${lot.id} too close to coast`).toBeGreaterThanOrEqual(15);
        expect(riverAt(x, z).d, `${lot.id} in Sallow River`).toBeGreaterThanOrEqual(25);
        expect(distToPolyline(x, z, RAIL), `${lot.id} on railway`).toBeGreaterThanOrEqual(12);
      }
    }
  });

  it('has zero overlapping lots and zero lots overlapping exclusive landmark plots', () => {
    const CELL = 40;
    const grid = new Map<string, number[]>();
    const bboxes = plan.lots.map((l) => {
      let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
      for (const [x, z] of l.corners) {
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
        if (z < minZ) minZ = z;
        if (z > maxZ) maxZ = z;
      }
      return { minX, maxX, minZ, maxZ };
    });

    for (let i = 0; i < plan.lots.length; i++) {
      const b = bboxes[i]!;
      for (let gx = Math.floor(b.minX / CELL); gx <= Math.floor(b.maxX / CELL); gx++) {
        for (let gz = Math.floor(b.minZ / CELL); gz <= Math.floor(b.maxZ / CELL); gz++) {
          const key = `${gx},${gz}`;
          const arr = grid.get(key);
          if (arr) arr.push(i);
          else grid.set(key, [i]);
        }
      }
    }

    const tested = new Set<number>();
    const n = plan.lots.length;
    for (const bucket of grid.values()) {
      for (let a = 0; a < bucket.length; a++) {
        for (let b = a + 1; b < bucket.length; b++) {
          const i = Math.min(bucket[a]!, bucket[b]!);
          const j = Math.max(bucket[a]!, bucket[b]!);
          const pairKey = i * n + j;
          if (tested.has(pairKey)) continue;
          tested.add(pairKey);

          const bi = bboxes[i]!;
          const bj = bboxes[j]!;
          if (bi.maxX < bj.minX || bj.maxX < bi.minX || bi.maxZ < bj.minZ || bj.maxZ < bi.minZ) {
            continue;
          }
          expect(
            polygonsOverlap(plan.lots[i]!.corners, plan.lots[j]!.corners, 0),
            `lots ${plan.lots[i]!.id} and ${plan.lots[j]!.id} overlap`,
          ).toBe(false);
        }
      }
    }

    for (const lm of plan.landmarkPlots) {
      if (!lm.exclusive) continue;
      for (const lot of plan.lots) {
        expect(
          polygonsOverlap(lot.corners, lm.corners, 0),
          `lot ${lot.id} overlaps landmark ${lm.id}`,
        ).toBe(false);
      }
    }
  });

  it('reserves a landmark plot for every landmark in site.ts with matching opening dates', () => {
    expect(plan.landmarkPlots.length).toBe(LANDMARKS.length);
    const plotById = new Map(plan.landmarkPlots.map((p) => [p.id, p]));
    for (const lm of LANDMARKS) {
      const plot = plotById.get(lm.id);
      expect(plot, `missing landmark plot for ${lm.id}`).toBeDefined();
      expect(plot!.name).toBe(lm.name);
      expect(plot!.from).toBe(lm.from);
      expect(plot!.corners.length).toBe(4);
    }
  });
});

describe('city2 / Dove Beach era ledger (1700s–2050s)', () => {
  it('is strictly deterministic from the seed and varies across different seeds', () => {
    const again = buildDoveBeachPlan(CITY2_SEED);
    expect(again.lots.length).toBe(plan.lots.length);
    expect(again.standingIn(1900)).toEqual(plan.standingIn(1900));
    expect(again.standingIn(1960)).toEqual(plan.standingIn(1960));
    expect(again.standingIn(2025)).toEqual(plan.standingIn(2025));

    const alt = buildDoveBeachPlan('dove-beach-alt-seed');
    expect(alt.standingIn(1960)).not.toEqual(plan.standingIn(1960));
  });

  it('follows the documented chronology from fishing hamlet to 2050s', () => {
    const countDist = (year: number, dist: string) =>
      plan.standingIn(year).buildings.filter((b) => b.districtId === dist).length;
    const hasLandmark = (year: number, id: string) =>
      plan.standingIn(year).landmarks.some((l) => l.id === id);

    expect(eraForYear(1760)).toBe('hamlet');
    expect(countDist(1760, 'harbour')).toBeGreaterThan(0);
    expect(countDist(1760, 'oldtown')).toBe(0);
    expect(plan.standingIn(1760).landmarks.length).toBe(0);

    expect(eraForYear(1850)).toBe('fort_harbour');
    expect(hasLandmark(1850, 'fort')).toBe(true);
    expect(hasLandmark(1850, 'customhouse')).toBe(true);
    expect(hasLandmark(1850, 'lighthouse')).toBe(false);
    expect(hasLandmark(1851, 'lighthouse')).toBe(true);
    expect(countDist(1850, 'oldtown')).toBeGreaterThan(0);
    expect(countDist(1850, 'mills')).toBe(0);

    expect(eraForYear(1900)).toBe('mills_rail');
    expect(countDist(1900, 'mills')).toBeGreaterThan(0);
    expect(hasLandmark(1900, 'brewery')).toBe(true);
    expect(hasLandmark(1900, 'station')).toBe(true);
    expect(hasLandmark(1900, 'observatory')).toBe(true);
    expect(hasLandmark(1900, 'funicular')).toBe(true);
    expect(countDist(1900, 'newtown')).toBe(0);
    expect(countDist(1900, 'strand')).toBe(0);
    expect(hasLandmark(1900, 'pavilion')).toBe(false);
    expect(hasLandmark(1900, 'fountain')).toBe(false);
    expect(countDist(1900, 'ridge')).toBe(0);
    expect(countDist(1900, 'fellside')).toBe(0);
    expect(countDist(1900, 'eastcove')).toBe(0);
    expect(hasLandmark(1900, 'shrimpdock')).toBe(false);

    expect(eraForYear(1925)).toBe('resort');
    expect(countDist(1925, 'newtown')).toBeGreaterThan(0);
    expect(countDist(1925, 'strand')).toBeGreaterThan(0);
    expect(hasLandmark(1925, 'pavilion')).toBe(true);
    expect(hasLandmark(1925, 'fountain')).toBe(true);
    expect(hasLandmark(1925, 'drawbridge')).toBe(true);
    expect(countDist(1925, 'ridge')).toBe(0);
    expect(countDist(1925, 'fellside')).toBe(0);
    expect(countDist(1925, 'eastcove')).toBe(0);
    expect(countDist(1925, 'uplands')).toBe(0);

    expect(eraForYear(1960)).toBe('car_boom');
    expect(countDist(1960, 'ridge')).toBeGreaterThan(0);
    expect(countDist(1960, 'fellside')).toBeGreaterThan(0);
    expect(countDist(1960, 'eastcove')).toBeGreaterThan(0);
    expect(hasLandmark(1960, 'shrimpdock')).toBe(true);
    expect(hasLandmark(1960, 'mast')).toBe(true);
    expect(countDist(1960, 'uplands')).toBe(0);
    expect(
      plan.standingIn(1960).buildings.some((b) => b.districtId === 'strand' && b.role === 'motor_court'),
    ).toBe(true);
    expect(
      plan.standingIn(1960).buildings.some((b) => b.districtId === 'head' && b.role === 'naval_barracks'),
    ).toBe(true);

    expect(eraForYear(1980)).toBe('decline');
    expect(countDist(1980, 'uplands')).toBeGreaterThan(0);
    expect(
      plan.standingIn(1980).buildings
        .filter((b) => b.districtId === 'mills')
        .every((b) => b.role === 'shuttered_mill'),
    ).toBe(true);

    expect(eraForYear(2000)).toBe('revival');
    expect(countDist(2000, 'head')).toBeLessThan(countDist(1960, 'head'));
    expect(
      plan.standingIn(2000).buildings.some(
        (b) => b.districtId === 'mills' && (b.role === 'mill_lofts' || b.role === 'taproom' || b.role === 'artist_studio'),
      ),
    ).toBe(true);

    expect(eraForYear(2025)).toBe('modern');
    expect(eraForYear(2050)).toBe('modern');
    expect(countDist(2025, 'marsh')).toBe(0);
    expect(countDist(2050, 'marsh')).toBe(0);
    expect(plan.standingIn(2025).buildings.length).toBeGreaterThan(plan.standingIn(1960).buildings.length);
  });

  it('caps every regular building at 4 storeys; only Head Light exceeds 4 storeys', () => {
    for (const entry of plan.lotLedger) {
      for (const stage of entry.stages) {
        expect(stage.storeys).toBeGreaterThanOrEqual(1);
        expect(stage.storeys).toBeLessThanOrEqual(4);
      }
    }
    const tallLandmarks = plan.landmarkLedger.filter((e) => e.stages.some((s) => s.storeys > 4));
    expect(tallLandmarks.map((e) => e.landmarkId)).toEqual(['lighthouse']);
  });
});

describe('city2 / Dove Beach top-down SVG era maps', () => {
  it('renders distinct valid SVGs for 1900, 1960, and 2025', () => {
    const svg1900 = renderDoveBeachSvg(plan, 1900);
    const svg1960 = renderDoveBeachSvg(plan, 1960);
    const svg2025 = renderDoveBeachSvg(plan, 2025);

    for (const [year, svg] of [
      [1900, svg1900],
      [1960, svg1960],
      [2025, svg2025],
    ] as const) {
      expect(svg.startsWith('<svg ')).toBe(true);
      expect(svg.trimEnd().endsWith('</svg>')).toBe(true);
      expect(svg).toContain(`Dove Beach — ${year}`);
      expect(svg).toContain('Heron Marsh');
    }

    expect(svg1900).not.toBe(svg1960);
    expect(svg1960).not.toBe(svg2025);
    expect(svg1960.length).toBeGreaterThan(svg1900.length);
    expect(svg2025.length).toBeGreaterThan(svg1960.length);
  });
});
