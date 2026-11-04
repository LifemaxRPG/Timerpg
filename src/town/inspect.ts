import { neighborhoodOf } from './neighborhoods';
import { ageIn, fullName, isAlive, type Id, type Person, type World } from '../sim/types';
import { landmarkDescription, lotDescription, type TownLayout } from './layout';

/** What the player finds when they click somewhere in a town: text, plus who/what they can act on. */
export interface Inspection {
  title: string;
  lines: string[];
  people: { id: Id; label: string }[];
  business: { id: Id; label: string } | null;
}

const label = (p: Person, year: number): string => `${fullName(p)} (${ageIn(p, year)})`;

/** Inspect the building or landmark at local tile (tx, ty), or null if there is nothing there. */
export function inspectAt(layout: TownLayout, w: World, tx: number, ty: number): Inspection | null {
  const lm = layout.landmarks.find((l) => tx >= l.x && tx < l.x + l.w && ty >= l.y && ty < l.y + l.h);
  if (lm) {
    const [title, ...lines] = landmarkDescription(w, lm);
    return { title: title!, lines, people: [], business: null };
  }
  const lot = [...layout.lots, ...layout.vacantLots].find((l) => tx >= l.x && tx < l.x + l.w && ty >= l.y && ty < l.y + l.h);
  if (!lot) return null;
  const district = neighborhoodOf(layout.plan, lot.neighborhood).name;
  if (lot.vacantSince !== undefined) return {
    title: lot.kind === 'shop' ? 'Vacant shop' : 'Vacant home',
    lines: [district, `Building recorded in ${lot.builtYear}`, `Vacant since ${lot.vacantSince}`, `Previously: ${lot.label}`],
    people: [], business: null,
  };
  const [title, ...lines] = lotDescription(w, lot);
  if (lot.kind === 'shop') {
    const b = w.businesses[lot.refId]!;
    const owner = b.owner ? w.people[b.owner] : undefined;
    return {
      title: title!, lines: [district, ...lines],
      people: owner && isAlive(owner) ? [{ id: owner.id, label: `${label(owner, w.year)}, owner` }] : [],
      business: { id: b.id, label: b.name },
    };
  }
  return {
    title: title!, lines: [district, ...(lot.builtYear === undefined ? [] : [`Building recorded in ${lot.builtYear}`])],
    people: lot.residents.map((id) => w.people[id]!).filter(isAlive).map((p) => ({ id: p.id, label: label(p, w.year) })),
    business: null,
  };
}

/** Living adults other than `except`, for choosing who to introduce someone to. */
export function adultsOf(w: World, except?: Id): { id: Id; label: string }[] {
  return Object.values(w.people)
    .filter((p) => isAlive(p) && ageIn(p, w.year) >= 18 && p.id !== except)
    .sort((a, b) => (a.surname + a.first).localeCompare(b.surname + b.first))
    .map((p) => ({ id: p.id, label: label(p, w.year) }));
}
