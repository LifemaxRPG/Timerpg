import { rand } from './rng';
import { affinity } from './social';
import { ageIn, fullName, isAlive, type Id, type Person, type World } from './types';

// SECRETS AND WHO KNOWS THEM. Some adults carry a secret. A secret is a fact about its subject that
// the simulation backs up (their money, their marriage, their rivals). Each is known to its subject,
// usually to their spouse, and to a few confidants chosen by how much they like each other; a very
// social secret keeper leaks a little more widely. Everything is a pure function of (seed, person),
// so the same town has the same secrets in every timeline, and only the facts behind them change
// when you rewrite history. Dialogue reads this; it never invents secrets.

export type SecretKind = 'debt' | 'affair' | 'grudge' | 'ambition' | 'past';

export interface Secret {
  /** Stable id: seed-and-person keyed, so a journal entry survives time travel. */
  id: string;
  about: Id;
  kind: SecretKind;
  /** The fact, in the third person ("owes money to ..."). */
  text: string;
  /** How badly it would hurt them if it got out, 0..1. */
  weight: number;
  /** Who else is part of it (an affair partner, a rival). */
  other?: Id;
}

const adults = (w: World): Person[] => Object.values(w.people).filter((p) => isAlive(p) && ageIn(p, w.year) >= 18);

const cache = new WeakMap<World, Map<Id, Secret[]>>();

/** The secrets a person carries this year (0 to 2). */
export function secretsOf(w: World, p: Person): Secret[] {
  let m = cache.get(w);
  if (!m) { m = new Map(); cache.set(w, m); }
  const hit = m.get(p.id);
  if (hit) return hit;
  const out: Secret[] = [];
  if (isAlive(p) && ageIn(p, w.year) >= 18) {
    const R = (...k: (string | number)[]): number => rand(w.seed, 'secret', p.id, ...k);
    const others = adults(w).filter((q) => q.id !== p.id);
    const pickOther = (salt: string, pred: (q: Person) => boolean = () => true): Person | undefined => {
      const pool = others.filter(pred);
      return pool.length ? pool[Math.floor(R(salt) * pool.length)] : undefined;
    };
    const mk = (kind: SecretKind, text: string, weight: number, other?: Person): void => {
      out.push({ id: `${w.seed}:${p.id}:${kind}`, about: p.id, kind, text, weight, ...(other ? { other: other.id } : {}) });
    };
    if (p.wealth < 400 && R('debt') < 0.7) mk('debt', 'is deep in debt and hiding how bad it is', 0.5);
    if (p.spouse && R('affair') < 0.14) {
      const lover = pickOther('lover', (q) => q.id !== p.spouse && q.sex !== p.sex);
      if (lover) mk('affair', `is secretly seeing ${fullName(lover)}`, 0.9, lover);
    }
    if (R('grudge') < 0.2) {
      const rival = pickOther('rival', (q) => affinity(w, p.id, q.id) < 0.1);
      if (rival) mk('grudge', `holds a bitter grudge against ${fullName(rival)}`, 0.4, rival);
    }
    if (p.traits.ambition > 0.75 && R('amb') < 0.5) mk('ambition', w.mayor && w.mayor !== p.id ? 'wants to take the mayor\'s seat' : 'dreams of running the whole town', 0.3);
    if (R('past') < 0.1) mk('past', 'ran from something in their past before coming here', 0.7);
  }
  m.set(p.id, out);
  return out;
}

const holdersCache = new WeakMap<World, Map<string, Id[]>>();

/** Everyone who knows this secret: the subject, their spouse, and a few confidants. */
export function holdersOf(w: World, s: Secret): Id[] {
  let m = holdersCache.get(w);
  if (!m) { m = new Map(); holdersCache.set(w, m); }
  const hit = m.get(s.id);
  if (hit) return hit;
  const subject = w.people[s.about]!;
  const holders = new Set<Id>([s.about]);
  if (subject.spouse && s.kind !== 'affair' && isAlive(w.people[subject.spouse]!)) holders.add(subject.spouse);
  if (s.other && s.kind === 'affair') holders.add(s.other);
  const friends = adults(w).filter((q) => q.id !== s.about && !holders.has(q.id))
    .map((q) => ({ id: q.id, a: affinity(w, s.about, q.id) + rand(w.seed, 'confide', s.id, q.id) * 0.4 }))
    .sort((x, y) => y.a - x.a || (x.id < y.id ? -1 : 1));
  const n = 1 + Math.floor(subject.traits.charm * 3) + (s.weight < 0.5 ? 2 : 0); // petty secrets leak
  for (const f of friends.slice(0, n)) holders.add(f.id);
  const result = [...holders];
  m.set(s.id, result);
  return result;
}

/** Secrets about OTHER people that this person knows. */
export function heardBy(w: World, p: Person): Secret[] {
  return adults(w)
    .filter((q) => q.id !== p.id)
    .flatMap((q) => secretsOf(w, q))
    .filter((s) => holdersOf(w, s).includes(p.id))
    .sort((a, b) => (a.id < b.id ? -1 : 1));
}

/**
 * How much they trust you enough to share, from how well they know you (chats). Charming people
 * open up sooner, gruff ones later.
 */
export const trustNeeded = (p: Person, secret: Secret, own: boolean): number =>
  Math.max(2, Math.round((own ? 4 : 2) + secret.weight * 2 - p.traits.charm * 1.5));
