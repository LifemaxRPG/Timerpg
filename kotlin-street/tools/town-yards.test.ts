// The yards of the whole town (tools/town-yards.ts) for the town of tools/town-config.ts: deterministic; every home lot
// with a front yard and a backyard; nothing overlapping a building, a street, another yard or another item of its layer;
// every driveway reaching its road across a dropped kerb; lanes and verges on free ground.
import { describe, expect, it } from 'vitest';
import { townGraph } from './town-graph';
import { townYards, type Rect4, type YardItem } from './town-yards';
import { TOWN } from './town-config';

const g = townGraph(TOWN.seed, TOWN.year);
const y = townYards(g);
const hit = (a: Rect4, b: Rect4, pad = 0.01): boolean => a[0] < b[2] - pad && b[0] < a[2] - pad && a[1] < b[3] - pad && b[1] < a[3] - pad;
const within = (a: Rect4, b: Rect4, pad = 0.01): boolean => a[0] >= b[0] - pad && a[1] >= b[1] - pad && a[2] <= b[2] + pad && a[3] <= b[3] + pad;
/** Each road segment as its whole corridor (building line to building line) and its carriageway (kerb to kerb). */
const corridors = g.graph.roads.flatMap((r) => r.pts.slice(1).map((b, i) => {
  const a = r.pts[i]!;
  const box = (h: number): Rect4 => [Math.min(a[0], b[0]) - h, Math.min(a[1], b[1]) - h, Math.max(a[0], b[0]) + h, Math.max(a[1], b[1]) + h];
  return { all: box(89), road: box(89 - 38) };
}));
/** Every building's own footprint (front wall to back). */
const bodies: { sprite: string; rect: Rect4 }[] = [];
for (const row of g.graph.rows) {
  const cum = [0]; for (let i = 1; i < row.pts.length; i++) cum.push(cum[i - 1]! + Math.hypot(row.pts[i]![0] - row.pts[i - 1]![0], row.pts[i]![1] - row.pts[i - 1]![1]));
  for (const b of row.buildings) {
    const s = Math.min(Math.max((b.x0 + b.x1) / 2, 0), cum[cum.length - 1]!); let i = 0;
    while (i + 2 < row.pts.length && s > cum[i + 1]!) i++;
    const p0 = row.pts[i]!, p1 = row.pts[i + 1]!, L = cum[i + 1]! - cum[i]!;
    const ux = (p1[0] - p0[0]) / L, uz = (p1[1] - p0[1]) / L, nx = uz, nz = -ux;
    const px = p0[0] + ux * (s - cum[i]!), pz = p0[1] + uz * (s - cum[i]!);
    const cs = [b.x0, b.x1].flatMap((u) => [b.setback, b.setback + b.depth].map((d) => [px + ux * (u - s) + nx * d, pz + uz * (u - s) + nz * d]));
    bodies.push({ sprite: b.sprite, rect: [Math.min(...cs.map((c) => c[0]!)), Math.min(...cs.map((c) => c[1]!)), Math.max(...cs.map((c) => c[0]!)), Math.max(...cs.map((c) => c[1]!))] });
  }
}

describe('town yards', () => {
  it('is deterministic', () => {
    expect(JSON.stringify(townYards(townGraph(TOWN.seed, TOWN.year)))).toBe(JSON.stringify(y));
  });

  it('gives every lot a parcel, and every home lot a front yard and a backyard', () => {
    expect(y.yards.map((v) => v.lot).sort()).toEqual(g.graph.lots.map((l) => l.id).sort());
    const bad: string[] = [];
    for (const v of y.yards) {
      const lot = g.graph.lots.find((l) => l.id === v.lot)!;
      if (!within(lot.rect, v.parcel)) bad.push(`${v.lot}: parcel does not hold its lot`);
      if (v.kind === 'shop') { if (!v.items.some((i) => i.zone === 'back')) bad.push(`${v.lot}: shop without a service yard`); continue; }
      // a block whose front stands on the building line (the condo stand-ins: setback 0) has the pavement for a front yard
      const flush = g.graph.rows.find((r) => r.id === v.row)!.buildings.find((b) => b.sprite === v.sprite)!.setback < 8;
      if (flush && v.kind === 'apartment') { if (v.front) bad.push(`${v.lot}: front yard on a flush front`); }
      else if (!v.front || !v.items.some((i) => i.zone === 'front' && i.layer === 'ground')) bad.push(`${v.lot}: no front yard`);
      if (!v.items.some((i) => i.zone === 'back' && i.layer === 'ground')) bad.push(`${v.lot}: no backyard`);
      if (v.back[2] - v.back[0] < 40 || v.back[3] - v.back[1] < 40) bad.push(`${v.lot}: backyard too small ${v.back}`);
      if (!v.items.some((i) => i.kind === 'backFence' || i.kind === 'hedge' || i.kind === 'postRail') && !y.yards.some((o) => o !== v && hit(o.parcel, [v.back[0] - 2, v.back[1] - 2, v.back[2] + 2, v.back[3] + 2], -0.5)))
        bad.push(`${v.lot}: backyard unfenced`);
    }
    expect(bad).toEqual([]);
  });

  it('keeps parcels, lanes and items off buildings, streets, water and each other', () => {
    const bad: string[] = [];
    const parcels = y.yards.map((v) => v.parcel);
    for (let i = 0; i < parcels.length; i++) for (let j = i + 1; j < parcels.length; j++) if (hit(parcels[i]!, parcels[j]!)) bad.push(`parcels ${y.yards[i]!.lot} / ${y.yards[j]!.lot}`);
    const water = (g.graph.water?.polys ?? []).map((p) => [p[0]![0], p[0]![1], p[2]![0], p[2]![1]] as Rect4);
    for (const v of y.yards) {
      for (const c of corridors) if (hit(v.parcel, c.all)) bad.push(`${v.lot}: parcel in a street`);
      for (const w of water) if (hit(v.parcel, w)) bad.push(`${v.lot}: parcel in the water`);
      for (const b of bodies) if (b.rect !== undefined && hit(v.parcel, b.rect) && b.sprite !== v.sprite) bad.push(`${v.lot}: parcel over ${b.sprite}`);
      for (const it of v.items) {
        const where = `${v.lot} ${it.kind}`;
        if (it.zone === 'street') { if (!within(it.rect, [v.parcel[0] - 40, v.parcel[1] - 40, v.parcel[2] + 40, v.parcel[3] + 40])) bad.push(`${where}: strays from its lot`); }
        else if (!within(it.rect, v.parcel)) bad.push(`${where}: outside its parcel`);
        for (const b of bodies) if (hit(it.rect, b.rect)) bad.push(`${where}: in building ${b.sprite}`);
        for (const c of corridors) if (hit(it.rect, it.zone === 'street' ? c.road : c.all)) bad.push(`${where}: in a street`);
        for (const o of y.yards) if (o !== v && hit(it.rect, o.parcel)) bad.push(`${where}: in yard ${o.lot}`);
      }
    }
    for (const l of y.lanes) {
      for (const p of y.yards) if (hit(l.rect, p.parcel)) bad.push(`${l.id}: over yard ${p.lot}`);
      for (const b of bodies) if (hit(l.rect, b.rect)) bad.push(`${l.id}: over ${b.sprite}`);
      for (const m of y.lanes) if (m !== l && hit(l.rect, m.rect)) bad.push(`${l.id}: over ${m.id}`);
      for (const c of corridors) if (hit(l.rect, c.all)) bad.push(`${l.id}: in a street`);
    }
    expect(bad).toEqual([]);
  });

  it('lays no item over another of its layer, and objects only on the surface they stand on', () => {
    const bad: string[] = [];
    const clash = (a: YardItem, b: YardItem): boolean => {
      const L = a.layer, M = b.layer;
      if (L === 'ground' || M === 'ground') return L === M;                                     // lawns tile the yard
      if (L === 'surface' && M === 'surface') return true;
      if (L === 'surface' || M === 'surface') { const [o, s] = L === 'surface' ? [b, a] : [a, b]; return o.on !== s.kind; }
      return true;                                                                               // objects and edges
    };
    for (const v of y.yards) {
      for (let i = 0; i < v.items.length; i++) for (let j = i + 1; j < v.items.length; j++) {
        const a = v.items[i]!, b = v.items[j]!;
        if (a.on === b.kind && within(a.rect, b.rect)) continue;                                 // a pool on its deck, a carport on its pad
        if (b.on === a.kind && within(b.rect, a.rect)) continue;
        if (hit(a.rect, b.rect) && clash(a, b)) bad.push(`${v.lot}: ${a.kind} over ${b.kind}`);
      }
    }
    expect(bad).toEqual([]);
  });

  it('runs every driveway from its road across a dropped kerb to a pad, carport or garage', () => {
    const bad: string[] = [];
    let n = 0;
    for (const v of y.yards) {
      const drives = v.items.filter((i) => i.kind === 'driveway');
      if (v.household.drive !== 'none' && v.kind !== 'shop' && drives.length === 0) bad.push(`${v.lot}: ${v.household.drive} without a driveway`);
      for (const d of drives) {
        n++;
        const kerb = v.items.find((i) => i.kind === 'kerbCut' && hit(i.rect, d.rect, -0.5));
        if (!kerb) { bad.push(`${v.lot}: driveway without a dropped kerb`); continue; }
        // the kerb cut reaches the carriageway of a road
        const grown: Rect4 = [kerb.rect[0] - 0.5, kerb.rect[1] - 0.5, kerb.rect[2] + 0.5, kerb.rect[3] + 0.5];
        if (!corridors.some((c) => hit(grown, c.road, 0))) bad.push(`${v.lot}: dropped kerb does not meet a road`);
        const end = v.items.find((i) => ['parkingPad', 'garage'].includes(i.kind) && hit([d.rect[0] - 0.5, d.rect[1] - 0.5, d.rect[2] + 0.5, d.rect[3] + 0.5], i.rect, 0));
        if (!end && v.household.drive !== 'garage') bad.push(`${v.lot}: driveway leads nowhere`);
      }
    }
    expect(bad).toEqual([]);
    expect(n).toBeGreaterThan(10);
  });

  it('dresses the neighbourhoods differently', () => {
    const by = new Map<string, Map<string, number>>();
    for (const v of y.yards) for (const i of v.items) {
      const m = by.get(v.neighbourhood) ?? new Map(); by.set(v.neighbourhood, m); m.set(i.kind, (m.get(i.kind) ?? 0) + 1);
    }
    expect(by.get('heights')?.get('hedge') ?? 0).toBeGreaterThan(0);
    expect(by.get('gardens')?.get('frontFence') ?? 0).toBeGreaterThan(0);
    expect((by.get('woodside')?.get('firePit') ?? 0) + (by.get('waterfront')?.get('firePit') ?? 0)).toBeGreaterThan(0);
    expect(y.yards.some((v) => v.household.children > 0 && v.items.some((i) => ['playSet', 'trampoline', 'sandpit'].includes(i.kind)))).toBe(true);
    expect(y.yards.some((v) => v.household.gardener && v.items.some((i) => i.kind === 'vegBed'))).toBe(true);
  });
});
