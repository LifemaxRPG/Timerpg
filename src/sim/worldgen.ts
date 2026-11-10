import { archetypeOf, pickShop } from './archetype';
import { BUSINESS_KINDS, FEMALE_NAMES, MALE_NAMES, SURNAMES } from './names';
import { clamp01, pick, rand, rngFor } from './rng';
import type { Business, Person, World } from './types';

/**
 * Creates one person. Everything about them (sex, name, traits) is derived from
 * (seed, id), so the "same" person is identical in every timeline where they exist.
 */
export function makePerson(
  seed: string,
  id: string,
  birthYear: number,
  surname: string,
  parents: Person[] = [],
  forcedSex?: 'F' | 'M',
): Person {
  const r = rngFor(seed, 'person', id);
  const sexRoll = r();
  const sex = forcedSex ?? (sexRoll < 0.5 ? 'F' : 'M');
  const first = pick(r, sex === 'F' ? FEMALE_NAMES : MALE_NAMES);
  const trait = (k: 'ambition' | 'charm' | 'diligence'): number => {
    const base = parents.length ? parents.reduce((s, p) => s + p.traits[k], 0) / parents.length : 0.5;
    return clamp01(base * 0.4 + r() * 0.6);
  };
  return {
    id,
    first,
    surname,
    sex,
    birthYear,
    traits: { ambition: trait('ambition'), charm: trait('charm'), diligence: trait('diligence') },
    parents: parents.map((p) => p.id),
    children: [],
    wealth: 0,
    reputation: 0,
  };
}

const FOUNDING_FAMILIES = 22;
const FOUNDING_SINGLES = 8;
const FOUNDING_BUSINESSES = 8;

/** Builds the starting town (ids prefixed `gen:`). Deterministic in `seed`. */
export function generateWorld(seed: string, startYear = 1950): World {
  const world: World = { seed, year: startYear, people: {}, businesses: {}, bonds: {} };
  const add = (p: Person): Person => {
    p.wealth = p.birthYear < startYear - 18 ? Math.round((5 + Math.floor(rand(seed, 'wealth', p.id) * 35)) * archetypeOf(seed).wealth) : 0;
    world.people[p.id] = p;
    return p;
  };

  for (let i = 0; i < FOUNDING_FAMILIES; i++) {
    const r = rngFor(seed, 'family', i);
    const surname = SURNAMES[i % SURNAMES.length] as string;
    const hBirth = 1900 + Math.floor(r() * 25);
    const wBirth = Math.max(1903, hBirth - 4 + Math.floor(r() * 8));
    const h = add(makePerson(seed, `gen:${i}:h`, hBirth, surname, [], 'M'));
    const w = add(makePerson(seed, `gen:${i}:w`, wBirth, surname, [], 'F'));
    h.spouse = w.id;
    w.spouse = h.id;
    const kids = Math.floor(r() * 4);
    for (let k = 0; k < kids; k++) {
      const cBirth = wBirth + 19 + Math.floor(r() * 12) + k * 2;
      if (cBirth >= startYear) continue;
      const c = add(makePerson(seed, `gen:${i}:c${k}`, cBirth, surname, [h, w]));
      c.parents = [h.id, w.id];
      h.children.push(c.id);
      w.children.push(c.id);
    }
  }

  for (let i = 0; i < FOUNDING_SINGLES; i++) {
    const r = rngFor(seed, 'single', i);
    const surname = SURNAMES[(i * 7 + 3) % SURNAMES.length] as string;
    add(makePerson(seed, `gen:s${i}`, 1905 + Math.floor(r() * 35), surname));
  }

  // The most ambitious working-age adults start the founding businesses.
  const founders = Object.values(world.people)
    .filter((p) => startYear - p.birthYear >= 25 && startYear - p.birthYear <= 60)
    .sort((a, b) => b.traits.ambition - a.traits.ambition || a.id.localeCompare(b.id))
    .slice(0, FOUNDING_BUSINESSES);
  founders.forEach((p, i) => {
    // The first dozen cover every kind (the shop art needs variety); beyond that the town's specialties win.
    const kind = i < BUSINESS_KINDS.length ? (BUSINESS_KINDS[(i + Math.floor(rand(seed, 'kind-shift') * BUSINESS_KINDS.length)) % BUSINESS_KINDS.length] as string) : pickShop(archetypeOf(seed), BUSINESS_KINDS, rand(seed, 'bkind', i));
    const b: Business = {
      id: `b:gen:${i}`,
      name: `${p.surname}'s ${kind}`,
      kind,
      foundedYear: startYear - 1 - Math.floor(rand(seed, 'bfound', i) * 15),
      owner: p.id,
      capital: 100 + Math.floor(rand(seed, 'bcap', i) * 300),
    };
    world.businesses[b.id] = b;
  });

  return world;
}
