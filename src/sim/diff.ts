import { fullName, isAlive, type Id, type World } from './types';

export interface WorldDiff {
  year: number;
  population: { before: number; after: number };
  /** People who exist in the original present but were never born / never came here. */
  erased: Id[];
  /** People who exist only in the altered timeline. */
  newcomers: Id[];
  mayor: { before?: Id; after?: Id };
  closedOnlyInAltered: Id[];
  closedOnlyInOriginal: Id[];
  businessesOnlyInAltered: Id[];
  businessesOnlyInOriginal: Id[];
  richest: { before?: Id; after?: Id };
}

const popCount = (w: World): number => Object.values(w.people).filter(isAlive).length;
const open = (w: World): Set<Id> =>
  new Set(Object.values(w.businesses).filter((b) => b.closedYear === undefined).map((b) => b.id));
const richest = (w: World): Id | undefined =>
  Object.values(w.people).filter(isAlive).sort((a, b) => b.wealth - a.wealth || (a.id < b.id ? -1 : 1))[0]?.id;

/** Compares two snapshots of the same year from different timelines. */
export function diffWorlds(original: World, altered: World): WorldDiff {
  const oPeople = new Set(Object.keys(original.people));
  const aPeople = new Set(Object.keys(altered.people));
  const oOpen = open(original);
  const aOpen = open(altered);
  const oBiz = new Set(Object.keys(original.businesses));
  const aBiz = new Set(Object.keys(altered.businesses));
  return {
    year: original.year,
    population: { before: popCount(original), after: popCount(altered) },
    erased: [...oPeople].filter((id) => !aPeople.has(id)).sort(),
    newcomers: [...aPeople].filter((id) => !oPeople.has(id)).sort(),
    mayor: { before: original.mayor, after: altered.mayor },
    closedOnlyInAltered: [...oOpen].filter((id) => aBiz.has(id) && !aOpen.has(id)).sort(),
    closedOnlyInOriginal: [...aOpen].filter((id) => oBiz.has(id) && !oOpen.has(id)).sort(),
    businessesOnlyInAltered: [...aBiz].filter((id) => !oBiz.has(id)).sort(),
    businessesOnlyInOriginal: [...oBiz].filter((id) => !aBiz.has(id)).sort(),
    richest: { before: richest(original), after: richest(altered) },
  };
}

export const isEmptyDiff = (d: WorldDiff): boolean =>
  d.erased.length === 0 && d.newcomers.length === 0 && d.mayor.before === d.mayor.after &&
  d.closedOnlyInAltered.length === 0 && d.closedOnlyInOriginal.length === 0 &&
  d.businessesOnlyInAltered.length === 0 && d.businessesOnlyInOriginal.length === 0 &&
  d.richest.before === d.richest.after && d.population.before === d.population.after;

/** Plain-text summary (a template now; an LLM can rephrase this structured diff later). */
export function describeDiff(d: WorldDiff, original: World, altered: World): string[] {
  const nameIn = (id: Id | undefined, ...ws: World[]): string => {
    if (!id) return 'nobody';
    for (const w of ws) if (w.people[id]) return fullName(w.people[id]!);
    return id;
  };
  const bizIn = (id: Id): string => (original.businesses[id] ?? altered.businesses[id])?.name ?? id;
  const list = (ids: string[], f: (id: string) => string, n = 4): string =>
    ids.slice(0, n).map(f).join(', ') + (ids.length > n ? `, +${ids.length - n} more` : '');
  const out: string[] = [`Population in ${d.year}: ${d.population.before} -> ${d.population.after}`];
  if (d.erased.length) out.push(`${d.erased.length} people never existed: ${list(d.erased, (i) => nameIn(i, original))}`);
  if (d.newcomers.length) out.push(`${d.newcomers.length} people exist only now: ${list(d.newcomers, (i) => nameIn(i, altered))}`);
  if (d.mayor.before !== d.mayor.after)
    out.push(`Mayor: ${nameIn(d.mayor.before, original)} -> ${nameIn(d.mayor.after, altered)}`);
  if (d.closedOnlyInAltered.length) out.push(`Closed down: ${list(d.closedOnlyInAltered, bizIn)}`);
  if (d.closedOnlyInOriginal.length) out.push(`Survived: ${list(d.closedOnlyInOriginal, bizIn)}`);
  if (d.businessesOnlyInOriginal.length) out.push(`Never founded: ${list(d.businessesOnlyInOriginal, bizIn)}`);
  if (d.businessesOnlyInAltered.length) out.push(`New businesses: ${list(d.businessesOnlyInAltered, bizIn)}`);
  if (d.richest.before !== d.richest.after)
    out.push(`Richest resident: ${nameIn(d.richest.before, original)} -> ${nameIn(d.richest.after, altered)}`);
  return out;
}
