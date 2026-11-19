// The buildings of a real town (from OUR game logic) under their sprite names bN, shared by tools/town-streets.ts (the
// two streets, street.json v1) and the whole-town export (street.json v2), so both name and dress every building the
// same way. makeBuildings() returns entry(lot), which names a lot's building (b0, b1, ... in the order entry is first
// called for each lot) and records its street.json entry, and vary(row), which re-dresses repeats along a row.
//
// Shops are drawn as the game draws them (shopFacade). A small or medium home wears the very mobile home the game draws
// on its lot (the engine builds it as a pitched-roof house: tools/make_buildings.py), and a villa its own villa (built
// with a front-facing gable, its terrace, balcony and porch), and a terraced house its own (a single-pitch roof), the
// one-story, country, Japanese and modern estates their own (modelled by hand), and an apartment block its own
// (condo_3_44/45, condo_5_*, condo_9: a flat roof, ledges, porch and steps); the other homes (other estates, the
// modular condo apartments) are still stood in by a modular condo front in the district's colour, until the engine can
// build them too. Every home is named after the household that really lives there.
import type { LandmarkPlot, Lot, TownLayout } from '../../src/town/layout';
import { landmarkArtKey } from '../../src/town/landmarks';
import { fullName, type World } from '../../src/sim/types';
import { shopFacade } from '../../src/town/facade';
import { stack, type Colour } from '../../src/town/buildings';
import { neighborhoodOf } from '../../src/town/neighborhoods';
import { houseArt } from '../../src/town/housing';
import { rand } from '../../src/sim/rng';

/** FNV-1a: a shop's look comes from its id (and the cross street's name from the seed and junction). */
export const hash = (s: string): number => { let h = 2166136261; for (const c of s) h = Math.imul(h ^ c.charCodeAt(0), 16777619); return (h >>> 0); };

export interface Buildings {
  /** Names a lot's building (its sprite bN), once; records its street.json entry the first time. */
  entry(l: Lot): string;
  /** Re-dresses repeated fronts along a row of sprite names (in place, in the entries). */
  vary(row: string[]): void;
  /**
   * Names a civic landmark's building (the next bN), once, and records its street.json entry: the sprite the game draws
   * for it (landmarkArtKey), as tools/make_buildings.py builds it, with the yard style of [yard]. Only the whole-town
   * export (street.json v2) names landmarks; v1 never calls this.
   */
  landmark(l: LandmarkPlot, yard: string): string;
  /** Every named building's street.json entry, in naming order (street.json `shops`). */
  entries: object[];
  /** Which lot each sprite name stands for. */
  lotOf: Map<string, Lot>;
}

/**
 * The lots that get a building, in the order the whole-town exports name them (after the v1 streets' own): occupied
 * shops, then every home (the same lots v1 picks from). One list for tools/town-graph.ts and tools/town-whole.ts, so
 * street.json v2, dialogue and interiors name every lot alike.
 */
export const townLots = (layout: TownLayout): Lot[] => [
  ...layout.lots.filter((l) => l.kind === 'shop' && l.vacantSince === undefined),
  ...layout.lots.filter((l) => l.kind === 'house'),
];

export function makeBuildings(w: World, layout: TownLayout, year: number): Buildings {
  const CONDO_ROOF: Record<Colour, number[]> = { red: [2], blue: [4], gray: [6] };
  /** A home's front: the modular condo stack (as housing.ts builds it) in the district's colour, one floor per two residents. */
  function homeFacade(colour: Colour, look: number, residents: number) {
    const ci = ['red', 'blue', 'gray'].indexOf(colour);
    const r = (salt: string): number => rand('street-home', salt, look.toFixed(6));
    // of each colour's four ground floors: 1 an apartment lobby, 2 an ATM, 3 a bank's arch, 4 a front door with two windows
    const ground = ci * 4 + (residents > 2 ? 1 : 4);
    const floors = Math.max(1, Math.min(2, Math.ceil(residents / 2)));
    const middles = Array.from({ length: floors }, (_, i) => ({ key: `middle_${ci * 6 + 1 + Math.floor(r(`m${i}`) * 6)}`, w: 224, h: 128 }));
    const roofs = CONDO_ROOF[colour];
    return stack([{ key: `condo_ground_${ground}`, w: 224, h: 96 }, ...middles, { key: `roof_${roofs[Math.floor(r('r') * roofs.length)]!}`, w: 224, h: 192 }]);
  }

  let n = 0;
  const seen = new Map<Lot, string>();
  const lotOf = new Map<string, Lot>();
  const entries: object[] = [];
  /** What each building was made from, so a repeat along a street can be made again differently (see vary()). */
  type Part = { key: string; w: number; h: number; y: number };
  type House = { tier: 'small' | 'medium'; design: number; colour: string };
  const made = new Map<string, { kind: string; look: number; colour: Colour; home: boolean; residents: number; parts: Part[];
    /** A mobile home (built by the engine as a pitched-roof house), with its lot's palette, and its street.json entry's house. */
    house?: House; palette?: Parameters<typeof houseArt>[3]; entry?: { house?: House };
    /** A villa (built by the engine with a front-facing gable): its sprite. */
    villa?: string;
    /** An apartment block wearing its own sprite (built by the engine under its flat roof): kept as the game has it. */
    block?: boolean }>();
  /** Which mobile home a sprite key is: house_small_7_c9 -> small, 7, c9 (the grey original has no colour). */
  const houseOf = (key: string): House | undefined => {
    const m = key.match(/^house_(small|medium)_(\d+)(?:_(c\d+))?$/);
    return m ? { tier: m[1] as 'small' | 'medium', design: Number(m[2]), colour: m[3] ?? '' } : undefined;
  };
  function entry(l: Lot): string {
    const had = seen.get(l);
    if (had) return had;
    const sprite = `b${n++}`;
    seen.set(l, sprite);
    lotOf.set(sprite, l);
    const colour = neighborhoodOf(layout.plan, l.neighborhood).shopColour;
    if (l.kind === 'shop') {
      const b = w.businesses[l.refId]!;
      const owner = b.owner ? w.people[b.owner] : undefined;
      // the very building our game draws for this shop: its sprite parts, bottom to top
      const look = (hash(b.id) % 1000000) / 1000000;
      const art = shopFacade(b.kind, look, year, colour);
      const parts = art.layers.map((ly) => ({ key: ly.key, w: ly.w, h: ly.h, y: ly.y }));
      made.set(sprite, { kind: b.kind, look, colour, home: false, residents: 0, parts });
      entries.push({ id: b.id, sprite, home: false, name: b.name, kind: b.kind, owner: owner ? fullName(owner) : '', colour: art.colour, parts });
    } else {
      const head = w.people[l.refId];
      const surname = head ? fullName(head).split(' ').slice(-1)[0] : '';
      // a small or medium home: the mobile home the game itself draws on this lot; a villa: its villa; any other: the
      // condo stand-in
      const mobile = (l.tier === 'small' || l.tier === 'medium') ? houseOf(l.art.layers[0]?.key ?? '') : undefined;
      const villa = l.tier === 'villa' && /^villa_\d+$/.test(l.art.layers[0]?.key ?? '') ? l.art.layers[0]!.key : undefined;
      // a terraced family house: its own (built by the engine as a single-pitch roof over its front)
      const terraced = l.tier === 'house' && /^house_terraced_\d+(_c\d+)?$/.test(l.art.layers[0]?.key ?? '');
      // an estate the engine builds (the one-story house, the country house): its own
      const estateA = l.tier === 'estate' && /^estate_(one_story|country)_house$/.test(l.art.layers[0]?.key ?? '');
      // the Japanese and the modern estate: their own (each modelled by hand from its parts: tools/buildings_estB.py)
      const estateB = l.tier === 'estate' && /^estate_(japanese|modern)_house$/.test(l.art.layers[0]?.key ?? '');
      // an apartment block: its own (built by the engine: its flat roof, ledges, porch and steps); the modular condo
      // stacks the game also draws for apartments stay as they are
      const block = l.tier === 'apartment' && /^condo_(3_4[45]|5_[123]|9)$/.test(l.art.layers[0]?.key ?? '');
      const built = mobile || villa || terraced || estateA || estateB || block;
      const art = built ? l.art : homeFacade(colour, l.look, l.residents.length);
      const parts = art.layers.map((ly) => ({ key: ly.key, w: ly.w, h: ly.h, y: ly.y }));
      const nb = neighborhoodOf(layout.plan, l.neighborhood);
      const e: Record<string, unknown> & { house?: House } = { id: `home:${l.slot}`, sprite, home: true, name: l.residents.length > 2 ? `${surname} House`.trim() : `The ${surname} home`.trim(),
        kind: 'Home', owner: head ? fullName(head) : '', residents: l.residents.map((id) => (w.people[id] ? fullName(w.people[id]!) : id)), colour,
        parts };
      // the house the engine builds, and its yard (the game's: how deep its front yard is, and how it is fenced)
      if (mobile) e.house = mobile;
      if (built) e.yard = { front: l.front, style: nb.yard };
      made.set(sprite, { kind: 'Home', look: l.look, colour, home: true, residents: l.residents.length, parts, house: mobile, palette: nb.palette, entry: e, villa, block });
      entries.push(e);
    }
    return sprite;
  }

  /**
   * No two buildings along a street wear the same front: a storefront that repeats one already on the street becomes another
   * of its kind's variants (or, if the kind has no other, the next colour family's); an upper floor that repeats becomes
   * another of its colour's six. The buildings, their order and their kinds stay as the town has them; only which of the art
   * pack's pictures each wears changes. Deterministic.
   */
  function vary(row: string[]): void {
    const usedGround = new Set<string>(); const usedMid = new Set<string>();
    const COLOURS: Colour[] = ['red', 'blue', 'gray'];
    const ground = (parts: { key: string }[]) => parts.find((p) => !p.key.startsWith('middle_') && !p.key.startsWith('roof'))?.key ?? '';
    /** The designs of the last two mobile homes or villas along the row (a colour of the same home is still the same home). */
    const recent: string[] = [];
    const designOf = (h: House): string => `${h.tier}_${h.design}`;
    for (const sprite of row) {
      const m = made.get(sprite); if (!m) continue;
      if (m.house) {
        // no mobile home repeats either of the two before it: another of its size in its district's colours instead
        // (as the game might have stood there), so a row of homes reads as neighbours, not copies. Deterministic.
        if (recent.includes(designOf(m.house))) {
          for (let a = 1; a <= 24; a++) {
            const art = houseArt(m.house.tier, (m.look + a * 0.6180339) % 1, year, m.palette);
            const h = houseOf(art.layers[0]?.key ?? '');
            if (!h || recent.includes(designOf(h))) continue;
            m.parts.splice(0, m.parts.length, ...art.layers.map((ly) => ({ key: ly.key, w: ly.w, h: ly.h, y: ly.y })));
            m.house = h; if (m.entry) m.entry.house = h;
            break;
          }
        }
        recent.push(designOf(m.house)); if (recent.length > 2) recent.shift();
        continue;
      }
      if (m.villa) {
        // a villa repeating one of the two homes before it: another of its district's villas, if it has another (a
        // district's palette allows one or two of the five)
        if (recent.includes(m.villa)) {
          for (let a = 1; a <= 24; a++) {
            const key = houseArt('villa', (m.look + a * 0.6180339) % 1, year, m.palette).layers[0]?.key ?? '';
            if (!/^villa_\d+$/.test(key) || recent.includes(key)) continue;
            m.parts.splice(0, m.parts.length, ...houseArt('villa', (m.look + a * 0.6180339) % 1, year, m.palette).layers.map((ly) => ({ key: ly.key, w: ly.w, h: ly.h, y: ly.y })));
            m.villa = key;
            break;
          }
        }
        recent.push(m.villa); if (recent.length > 2) recent.shift();
        continue;
      }
      if (m.block) continue;
      if (usedGround.has(ground(m.parts))) {
        let found: { key: string; w: number; h: number; y: number }[] | null = null;
        const order = [m.colour, ...COLOURS.filter((c) => c !== m.colour)];
        for (const c of order) {
          for (let a = 1; a <= 8 && !found; a++) {
            const look = (m.look + a * 0.6180339) % 1;
            const parts = m.home ? homeFacade(c, look, m.residents).layers : shopFacade(m.kind, look, year, c).layers;
            const ps = parts.map((ly) => ({ key: ly.key, w: ly.w, h: ly.h, y: ly.y }));
            if (!usedGround.has(ground(ps))) found = ps;
          }
          if (found) break;
        }
        if (found) m.parts.splice(0, m.parts.length, ...found);
      }
      usedGround.add(ground(m.parts));
      // upper floors: another of the same colour's six middles when this one is already on the street
      for (const p of m.parts) {
        if (!p.key.startsWith('middle_')) continue;
        if (usedMid.has(p.key)) {
          const n = Number(p.key.slice(7)); const band = Math.floor((n - 1) / 6);
          for (let k = 1; k < 6; k++) {
            const alt = `middle_${band * 6 + 1 + ((n - 1 + k) % 6)}`;
            if (!usedMid.has(alt)) { p.key = alt; break; }
          }
        }
        usedMid.add(p.key);
      }
    }
  }
  const landmarks = new Map<string, string>();
  function landmark(l: LandmarkPlot, yard: string): string {
    const had = landmarks.get(l.id);
    if (had) return had;
    const sprite = `b${n++}`;
    landmarks.set(l.id, sprite);
    entries.push({ id: `landmark:${l.id}`, sprite, home: false, name: l.name, kind: 'Landmark', owner: '', colour: 'base',
      parts: [{ key: landmarkArtKey(l), w: l.w * 32, h: l.h * 32, y: 0 }], yard: { front: 2, style: yard } });
    return sprite;
  }
  return { entry, vary, landmark, entries, lotOf };
}
