import { archetypeOf, foundedOf, type ArchetypeId } from './archetype';
import { rand } from './rng';
import { affinity, clampRep, pairKey } from './social';
import { makePerson } from './worldgen';
import { ageIn, fullName, isAlive, type Business, type Id, type Person, type Story, type StoryCategory, type World } from './types';

// THE SCENARIO ENGINE. Every year a few stories happen in town. A story is a TEMPLATE (a feud, a fire, a
// returning native, a flood ...) whose roles are filled by real people and businesses of the town, whose
// outcome is rolled, whose wording is assembled from era-specific phrase pools, and whose effects are real:
// reputations move, money changes hands, people fall out, shops burn, someone dies. Some stories leave an open
// thread that a later story resolves years afterwards (the missing person turns up, the mystery is solved).
//
// Everything is keyed random (rng.ts): who is picked for a role is the eligible person with the highest hash
// for that role, so removing one person from the past changes only the stories that person was in.

export interface Ctx { w: World; y: number }

interface Made {
  headline: string;
  body: string;
  people: Id[];
  business?: Id;
  open?: boolean;
  sequelOf?: string;
  apply?: () => void;
}

interface S {
  w: World;
  y: number;
  slot: number;
  type: ArchetypeId;
  die: (p: Person, cause: 'natural' | 'murdered') => void;
  /** Keyed roll 0..1 for this story. */
  r: (...k: (string | number)[]) => number;
  /** Everyone alive (sorted by id, computed once per year). */
  all: Person[];
  adults: Person[];
  shops: Business[];
}

interface Template {
  id: string;
  cat: StoryCategory;
  /** How likely this story is in this town this year (0 = cannot happen). */
  weight: (s: S) => number;
  /** Roles filled by people: how many people (or shops) it draws from the town, for the space estimate. */
  roles: ('person' | 'pair' | 'shop' | 'pairShop' | 'none')[];
  /** Number of outcomes times phrase variants, for the space estimate. */
  variants: number;
  make: (s: S) => Made | null;
}

// ---------- helpers ----------

const living = (w: World): Person[] => Object.values(w.people).filter(isAlive).sort((a, b) => (a.id < b.id ? -1 : 1));
const open = (w: World): Business[] => Object.values(w.businesses).filter((b) => b.closedYear === undefined).sort((a, b) => (a.id < b.id ? -1 : 1));

/** The eligible item with the highest keyed hash: stable when unrelated people come and go. */
function choose<T extends { id: Id }>(s: S, key: string, list: readonly T[], skip: readonly Id[] = []): T | undefined {
  let best: T | undefined, bv = -1;
  for (const t of list) {
    if (skip.includes(t.id)) continue;
    const v = rand(s.w.seed, s.y, 'role', s.slot, key, t.id);
    if (v > bv) { bv = v; best = t; }
  }
  return best;
}
/** One of several phrasings, by keyed roll. */
const v = <T,>(s: S, key: string, opts: readonly T[]): T => opts[Math.floor(s.r('phrase', key) * opts.length) % opts.length]!;
const nm = fullName;
const bump = (w: World, a: Id, b: Id, d: number): void => { const k = pairKey(a, b); w.bonds[k] = (w.bonds[k] ?? 0) + d; };
const rep = (p: Person, d: number): void => { p.reputation = clampRep(p.reputation + d); };
const married = (p: Person): boolean => p.spouse !== undefined;
const heShe = (p: Person): string => (p.sex === 'F' ? 'she' : 'he');
const hisHer = (p: Person): string => (p.sex === 'F' ? 'her' : 'his');

/** Words that change with the decade, so 1953 and 2019 do not read alike. */
const era = (y: number) => ({
  call: y < 1960 ? 'a letter' : y < 1990 ? 'a phone call' : y < 2005 ? 'a message on the machine' : 'a text message',
  news: y < 1990 ? 'the Gazette' : y < 2008 ? 'the Courier' : 'the neighborhood group chat',
  car: y < 1962 ? 'a Packard' : y < 1985 ? 'a station wagon' : 'a pickup truck',
  show: y < 1955 ? 'the radio' : y < 2000 ? 'the television' : 'a video online',
  pay: y < 1975 ? 'dollars' : 'dollars',
  gather: y < 1975 ? 'the church social' : y < 2000 ? 'the diner' : 'the coffee shop',
});

/** Recently dead parents with 2+ living children whose estate has not yet been fought over. */
function estates(s: S): Person[] {
  const done = new Set((s.w.stories ?? []).filter((x) => x.template === 'inheritance_dispute').flatMap((x) => x.people));
  return Object.values(s.w.people)
    .filter((p) => p.deathYear !== undefined && p.deathYear >= s.y - 3 && p.deathYear < s.y)
    .filter((p) => { const k = p.children.map((c) => s.w.people[c]).filter((q): q is Person => !!q && isAlive(q)); return k.length >= 2 && !k.some((q) => done.has(q.id)); })
    .sort((a, b) => (a.id < b.id ? -1 : 1));
}

// ---------- the library ----------

const T: Template[] = [
  // ===== romance =====
  {
    id: 'courtship', cat: 'romance', roles: ['pair'], variants: 3 * 4 * 3, weight: (s) => (s.adults.filter((p) => !married(p) && ageIn(p, s.y) < 48).length >= 2 ? 2.2 : 0),
    make: (s) => {
      const singles = s.adults.filter((p) => !married(p) && ageIn(p, s.y) < 48);
      const a = choose(s, 'a', singles), b = a && choose(s, 'b', singles.filter((p) => p.sex !== a.sex), [a.id]);
      if (!a || !b) return null;
      const o = s.r('o');
      const place = v(s, 'place', ['at the church social', 'at the dance hall', 'walking by the plaza fountain', 'at the lake', 'outside the bakery']);
      if (o < 0.6) return { headline: v(s, 'h', [`${a.first} ${a.surname} and ${b.first} ${b.surname} seen together`, `Is it romance for ${a.first} and ${b.first}?`, `${a.surname}–${b.surname}: a courtship to watch`, `Sparks between ${a.first} and ${b.first}`]), body: `Tongues are wagging: ${nm(a)} and ${nm(b)} were seen ${place}, ${v(s, 'c', ['laughing like old friends', 'hand in hand', 'deep in conversation'])}. ${era(s.y).news[0]!.toUpperCase()}${era(s.y).news.slice(1)} hears wedding bells are not out of the question.`, people: [a.id, b.id], apply: () => bump(s.w, a.id, b.id, 0.45) };
      if (o < 0.85) return { headline: `A slow start for ${a.first} and ${b.first}`, body: `${nm(a)} asked ${nm(b)} to ${place.replace('at ', '').replace('by ', '').replace('outside ', '')} and it did not go well. They say they will try again.`, people: [a.id, b.id], apply: () => bump(s.w, a.id, b.id, -0.1) };
      return { headline: `${a.first} ${a.surname} turned down`, body: `${nm(b)} gave ${nm(a)} a firm no ${place}. ${nm(a)} has been seen ${v(s, 'sad', ['walking alone by the river', 'at the diner counter for hours', 'staring at the plaza fountain'])} ever since.`, people: [a.id, b.id], apply: () => { bump(s.w, a.id, b.id, -0.3); rep(a, -0.03); } };
    },
  },
  {
    id: 'affair_whisper', cat: 'romance', roles: ['pair'], variants: 4 * 3, weight: (s) => (s.adults.filter(married).length >= 4 ? 0.9 : 0),
    make: (s) => {
      const a = choose(s, 'a', s.adults.filter(married)), b = a && choose(s, 'b', s.adults.filter((p) => p.sex !== a.sex && p.id !== a.spouse), [a.id]);
      if (!a || !b) return null;
      const spouse = a.spouse ? s.w.people[a.spouse] : undefined;
      return {
        headline: v(s, 'h', [`Whispers about ${a.first} ${a.surname} and ${b.first} ${b.surname}`, `What is going on between ${a.first} and ${b.first}?`, `Late-night talk: ${a.surname} and ${b.surname}`]),
        body: `${v(s, 'o', ['Nobody will say it to their face, but', 'Over the fence and across the counter,', 'At the diner, in low voices,'])} the town is talking about ${nm(a)}${spouse ? `, who is married to ${nm(spouse)},` : ''} and ${nm(b)}. ${v(s, 'e', ['A late-night visit was noticed.', 'A letter was found where it should not have been.', `${era(s.y).call[0]!.toUpperCase()}${era(s.y).call.slice(1)} was overheard.`])}`,
        people: [a.id, b.id], open: true,
        apply: () => { rep(a, -0.22); rep(b, -0.18); if (spouse) bump(s.w, a.id, spouse.id, -0.35); },
      };
    },
  },
  {
    id: 'late_love', cat: 'romance', roles: ['pair'], variants: 3, weight: (s) => (s.adults.filter((p) => !married(p) && ageIn(p, s.y) >= 55).length >= 2 ? 0.8 : 0),
    make: (s) => {
      const old = s.adults.filter((p) => !married(p) && ageIn(p, s.y) >= 55);
      const a = choose(s, 'a', old), b = a && choose(s, 'b', old, [a.id]);
      if (!a || !b) return null;
      return { headline: `Never too late for ${a.first} and ${b.first}`, body: `Two familiar faces of ${v(s, 'g', ['the Sunday service', 'the plaza benches', 'the early-morning coffee crowd'])}, ${nm(a)} and ${nm(b)}, have been inseparable all season. Friends say they have not seen either of them smile like this in years.`, people: [a.id, b.id], apply: () => { bump(s.w, a.id, b.id, 0.5); rep(a, 0.04); rep(b, 0.04); } };
    },
  },
  // ===== feud & friendship =====
  {
    id: 'fence_dispute', cat: 'feud', roles: ['pair'], variants: 3 * 3 * 3, weight: (s) => (s.adults.length >= 8 ? 2 : 0),
    make: (s) => {
      const a = choose(s, 'a', s.adults), b = a && choose(s, 'b', s.adults.filter((p) => affinity(s.w, a.id, p.id) < 0.25 && p.id !== a.spouse), [a.id]);
      if (!a || !b) return null;
      const what = v(s, 'what', ['a property line', 'a tree that drops leaves in the wrong yard', 'a barking dog', 'a borrowed ladder that was never returned', 'noise late at night']);
      const o = s.r('o');
      if (o < 0.4) return { headline: `${a.surname}–${b.surname} dispute over ${what}`, body: `${nm(a)} and ${nm(b)} had words about ${what}. By evening, ${v(s, 'r', ['they had shaken hands over a pie', 'an apology had crossed the fence', 'a casserole had gone next door'])}.`, people: [a.id, b.id], apply: () => bump(s.w, a.id, b.id, 0.3) };
      if (o < 0.8) return { headline: `${a.surname}–${b.surname} feud grows`, body: `The quarrel over ${what} between ${nm(a)} and ${nm(b)} has not been settled. ${v(s, 'r', ['Neighbors have started choosing sides.', 'They no longer speak at church.', 'Both have hired surveyors, which settles nothing.'])}`, people: [a.id, b.id], open: true, apply: () => { bump(s.w, a.id, b.id, -0.5); rep(a, -0.05); rep(b, -0.05); } };
      const fee = Math.min(a.wealth, b.wealth, 20) * 0.5;
      return { headline: `${a.surname} v. ${b.surname}`, body: `It is the courthouse for ${nm(a)} and ${nm(b)} over ${what}. Only the lawyers are expected to come out ahead.`, people: [a.id, b.id], apply: () => { a.wealth -= fee; b.wealth -= fee; bump(s.w, a.id, b.id, -0.6); } };
    },
  },
  {
    id: 'shop_rivalry', cat: 'feud', roles: ['pairShop'], variants: 3 * 3, weight: (s) => (s.shops.length >= 4 ? 1.4 : 0),
    make: (s) => {
      const a = choose(s, 'a', s.shops.filter((b) => b.owner)), b = a && choose(s, 'b', s.shops.filter((x) => x.owner && x.owner !== a.owner), [a.id]);
      if (!a || !b) return null;
      const oa = s.w.people[a.owner!]!, ob = s.w.people[b.owner!]!;
      const o = s.r('o');
      if (o < 0.5) return { headline: `Price war: ${a.name} vs ${b.name}`, body: `${nm(oa)} and ${nm(ob)} have been undercutting each other for weeks. Customers are delighted; the owners are not.`, people: [oa.id, ob.id], business: a.id, apply: () => { a.capital *= 0.88; b.capital *= 0.88; bump(s.w, oa.id, ob.id, -0.4); } };
      const [win, lose] = rand(s.w.seed, s.y, 'win', a.id, b.id) < 0.5 ? [a, b] : [b, a];
      return { headline: `${win.name} pulls ahead of ${lose.name}`, body: `After a season of competition, ${win.name} is where the town shops now. ${nm(s.w.people[lose.owner!]!)} says it is only temporary.`, people: [win.owner!, lose.owner!], business: win.id, apply: () => { const t = lose.capital * 0.2; lose.capital -= t; win.capital += t * 0.8; bump(s.w, win.owner!, lose.owner!, -0.3); } };
    },
  },
  {
    id: 'rumor_mill', cat: 'feud', roles: ['person'], variants: 6, weight: () => 1.1,
    make: (s) => {
      const a = choose(s, 'a', s.adults);
      if (!a) return null;
      const what = v(s, 'w', ['is not as well off as they seem', 'was seen leaving the tavern at dawn', 'is not who they claim to be', 'cheats at cards', 'tells tall tales about the war', 'has been seen talking to the bank']);
      const true_ = s.r('true') < 0.5;
      return { headline: `They say ${a.first} ${a.surname} ${what}`, body: `${era(s.y).news[0]!.toUpperCase()}${era(s.y).news.slice(1)} cannot confirm it, but around ${era(s.y).gather} the story goes that ${nm(a)} ${what}. ${true_ ? 'Some say there is smoke where there is fire.' : 'Friends call it an outright lie.'}`, people: [a.id], apply: () => rep(a, true_ ? -0.18 : -0.08) };
    },
  },
  {
    id: 'friendship', cat: 'feud', roles: ['pair'], variants: 4, weight: (s) => (s.adults.length >= 6 ? 1.2 : 0),
    make: (s) => {
      const a = choose(s, 'a', s.adults), b = a && choose(s, 'b', s.adults.filter((p) => affinity(s.w, a.id, p.id) > 0.15), [a.id]);
      if (!a || !b) return null;
      return { headline: `${a.first} and ${b.first}: fast friends`, body: `${nm(a)} and ${nm(b)} ${v(s, 'o', ['started a weekly card game', 'built a porch together', 'took up fishing side by side', 'organized the street cleanup'])} and have been inseparable since.`, people: [a.id, b.id], apply: () => bump(s.w, a.id, b.id, 0.3) };
    },
  },
  // ===== business =====
  {
    id: 'shop_boom', cat: 'business', roles: ['shop'], variants: 4, weight: (s) => (s.shops.length ? 1.6 : 0),
    make: (s) => {
      const b = choose(s, 'b', s.shops);
      if (!b) return null;
      const o = b.owner ? s.w.people[b.owner] : undefined;
      return { headline: `Lines out the door at ${b.name}`, body: `${o ? nm(o) : 'The owner'} could hardly keep up this week at ${b.name}. ${v(s, 'r', ['A glowing mention did wonders.', 'A visitor told everyone they knew.', 'The new window display stopped traffic.', `${era(s.y).show[0]!.toUpperCase()}${era(s.y).show.slice(1)} gave it a kind word.`])}`, people: o ? [o.id] : [], business: b.id, apply: () => { b.capital *= 1.35; if (o) rep(o, 0.06); } };
    },
  },
  {
    id: 'shop_fire', cat: 'business', roles: ['shop'], variants: 2 * 3, weight: (s) => (s.shops.length ? 0.55 : 0),
    make: (s) => {
      const b = choose(s, 'b', s.shops);
      if (!b) return null;
      const o = b.owner ? s.w.people[b.owner] : undefined;
      const insured = s.r('ins') < 0.5;
      const cause = v(s, 'c', ['a faulty stove', 'an overturned lamp', 'wiring from before the war', 'a stray cigarette']);
      return { headline: `Fire at ${b.name}`, body: `A fire, blamed on ${cause}, gutted ${b.name} overnight. ${o ? nm(o) : 'The owner'} ${insured ? 'was insured and plans to rebuild' : 'had no insurance, and the town is passing the hat'}.`, people: o ? [o.id] : [], business: b.id, apply: () => { b.capital *= insured ? 0.7 : 0.25; if (o && !insured) rep(o, 0.04); } };
    },
  },
  {
    id: 'robbery', cat: 'crime', roles: ['shop', 'person'], variants: 3, weight: (s) => (s.shops.length ? 0.8 : 0),
    make: (s) => {
      const b = choose(s, 'b', s.shops);
      const sus = choose(s, 'sus', s.adults.filter((p) => p.reputation < 0.1 && p.wealth < 30));
      if (!b) return null;
      const o = b.owner ? s.w.people[b.owner] : undefined;
      return { headline: `Break-in at ${b.name}`, body: `${o ? nm(o) : 'The owner'} found the till empty at ${b.name} this morning. ${sus ? `Eyes have turned to ${nm(sus)}, though nothing is proven.` : 'The sheriff has no suspects.'}`, people: [...(o ? [o.id] : []), ...(sus ? [sus.id] : [])], business: b.id, open: !!sus, apply: () => { b.capital = Math.max(0, b.capital - 30); if (sus) rep(sus, -0.28); } };
    },
  },
  {
    id: 'embezzlement', cat: 'crime', roles: ['shop'], variants: 3, weight: (s) => (s.shops.some((b) => b.kind === 'Bank' && b.owner) ? 0.4 : 0),
    make: (s) => {
      const b = choose(s, 'b', s.shops.filter((x) => x.kind === 'Bank' && x.owner));
      if (!b) return null;
      const o = s.w.people[b.owner!]!;
      return { headline: `${b.name}: the books do not balance`, body: `Auditors found ${v(s, 'a', ['a hole in the ledger', 'accounts that do not exist', 'a vault that is lighter than it should be'])} at ${b.name}. ${nm(o)} insists it is a clerical error. Depositors are lining up to withdraw.`, people: [o.id], business: b.id, apply: () => { b.capital *= 0.55; rep(o, -0.4); } };
    },
  },
  {
    id: 'inventor', cat: 'business', roles: ['person'], variants: 6, weight: (s) => (s.adults.some((p) => p.traits.ambition > 0.6) ? 0.7 : 0),
    make: (s) => {
      const a = choose(s, 'a', s.adults.filter((p) => p.traits.ambition > 0.6));
      if (!a) return null;
      const thing = v(s, 't', ['a better mousetrap', 'a folding ladder', 'a self-winding clock', 'a quieter lawn mower', 'a weatherproof paint', 'a smarter garden sprinkler']);
      const hit = s.r('hit') < 0.55;
      return { headline: `${a.first} ${a.surname} patents ${thing}`, body: `${nm(a)} spent years in ${hisHer(a)} workshop on ${thing}. ${hit ? 'A manufacturer has bought the rights.' : 'So far, no buyers.'}`, people: [a.id], apply: () => { if (hit) a.wealth += 60 + Math.floor(s.r('amt') * 90); rep(a, hit ? 0.1 : 0.02); } };
    },
  },
  {
    id: 'lottery', cat: 'celebration', roles: ['person'], variants: 3, weight: (s) => (s.y >= 1965 ? 0.5 : 0.15),
    make: (s) => {
      const a = choose(s, 'a', s.adults);
      if (!a) return null;
      const amt = 100 + Math.floor(s.r('amt') * 200);
      return { headline: v(s, 'h', [`${a.first} ${a.surname} wins big`, `Luck of the ${a.surname}s`, `${a.first} strikes it lucky`]), body: `${nm(a)} ${v(s, 'h', ['held the lucky ticket', 'won the raffle at the fair', 'hit the jackpot'])} and went home ${amt} richer. ${a.wealth < 20 ? `${heShe(a)[0]!.toUpperCase()}${heShe(a).slice(1)} says it could not have come at a better time.` : 'Friends are suddenly very friendly.'}`, people: [a.id], apply: () => { a.wealth += amt; } };
    },
  },
  {
    id: 'plant_closing', cat: 'business', roles: ['none'], variants: 3, weight: (s) => ((s.type === 'mill' || s.type === 'mining') ? (s.y >= 1975 ? 1.5 : 0.25) : 0.05),
    make: (s) => {
      const hit = s.adults.filter((p) => rand(s.w.seed, s.y, 'laid-off', p.id) < 0.3);
      return { headline: `Layoffs shake the town`, body: `${v(s, 'a', ['The works have cut a shift', 'The company announced cutbacks', 'Hundreds of hours of work vanished overnight'])}. ${hit.length} households feel it first.${hit[0] ? ` ${nm(hit[0])} says it is the worst ${hisHer(hit[0])} family has seen.` : ''}`, people: hit.slice(0, 3).map((p) => p.id), apply: () => { for (const p of hit) p.wealth *= 0.75; } };
    },
  },
  // ===== crime & mystery =====
  {
    id: 'petty_theft', cat: 'crime', roles: ['person', 'shop'], variants: 4, weight: (s) => (s.adults.some((p) => p.wealth < 15) && s.shops.length ? 0.9 : 0),
    make: (s) => {
      const a = choose(s, 'a', s.adults.filter((p) => p.wealth < 15)), b = choose(s, 'b', s.shops);
      if (!a || !b) return null;
      const caught = s.r('caught') < 0.55;
      return caught
        ? { headline: v(s, 'h', [`${a.first} ${a.surname} caught red-handed`, `${a.surname} in a spot of trouble`, `Sticky fingers at ${b.name}`]), body: `${nm(a)} was caught slipping ${v(s, 'i', ['a loaf of bread', 'a coat', 'a few dollars from the till', 'a tin of tobacco'])} from ${b.name}. The owner chose not to press charges, but the town has noted it.`, people: [a.id], business: b.id, apply: () => rep(a, -0.35) }
        : { headline: `Thief strikes ${b.name}`, body: `Small things keep going missing from ${b.name}. The owner suspects a regular customer and is keeping a close eye.`, people: [], business: b.id, open: true, apply: () => { b.capital = Math.max(0, b.capital - 8); } };
    },
  },
  {
    id: 'missing_person', cat: 'crime', roles: ['person'], variants: 4, weight: (s) => (s.adults.length >= 10 ? 0.35 : 0),
    make: (s) => {
      const a = choose(s, 'a', s.adults.filter((p) => ageIn(p, s.y) < 70));
      if (!a) return null;
      return { headline: `${a.first} ${a.surname} missing`, body: `${nm(a)} has not been seen since ${v(s, 'w', ['Tuesday evening', 'the church social', 'the fair', 'last weekend'])}. ${hisHer(a)[0]!.toUpperCase()}${hisHer(a).slice(1)} coat was found by ${v(s, 'p', ['the river', 'the old road', 'the railroad crossing'])}. ${v(s, 'e', ['Volunteers are searching the woods.', 'The sheriff asks anyone with information to come forward.', 'Neighbors have organized search parties.'])}`, people: [a.id], open: true, apply: () => { rep(a, 0.05); } };
    },
  },
  {
    id: 'murder_mystery', cat: 'crime', roles: ['pair'], variants: 4, weight: (s) => (s.adults.length >= 20 ? 0.12 : 0),
    make: (s) => {
      const vic = choose(s, 'v', s.adults.filter((p) => ageIn(p, s.y) < 75));
      const cul = vic && choose(s, 'c', s.adults.filter((p) => affinity(s.w, vic.id, p.id) < 0.05 && p.id !== vic.spouse), [vic.id]);
      if (!vic || !cul) return null;
      return { headline: `${vic.first} ${vic.surname} found dead: foul play suspected`, body: `${nm(vic)} was found ${v(s, 'w', ['on the old road', 'behind the shuttered cannery', 'in the orchard', 'by the water'])} this morning. The sheriff says it was no accident. ${v(s, 'e', ['No one will say who might have wanted this.', 'Everyone has a theory.', 'The town is locking its doors tonight.'])}`, people: [vic.id, cul.id], open: true, apply: () => { s.die(vic, 'murdered'); } };
    },
  },
  {
    id: 'vandalism', cat: 'crime', roles: ['person'], variants: 4, weight: (s) => (s.all.some((p) => ageIn(p, s.y) >= 13 && ageIn(p, s.y) < 18) ? 0.8 : 0),
    make: (s) => {
      const k = choose(s, 'k', s.all.filter((p) => ageIn(p, s.y) >= 13 && ageIn(p, s.y) < 18));
      if (!k) return null;
      return { headline: v(s, 'h', ['Prank goes too far', 'Town hall not amused', 'Mischief on Main Street', 'The great mailbox mystery']), body: `Someone ${v(s, 'a', ['painted the water tower', 'put a cow on the school roof', 'tipped over every mailbox on Main Street', 'wrote a name on the clock tower'])}. Word is ${k.first} ${k.surname} (${ageIn(k, s.y)}) was in on it.`, people: [k.id], apply: () => rep(k, -0.1) };
    },
  },
  // ===== civic =====
  {
    id: 'mayor_scandal', cat: 'civic', roles: ['none'], variants: 4, weight: (s) => (s.w.mayor && s.w.people[s.w.mayor] && isAlive(s.w.people[s.w.mayor]!) ? 0.7 : 0),
    make: (s) => {
      const m = s.w.people[s.w.mayor!]!;
      return { headline: v(s, 'h', [`Questions for Mayor ${m.surname}`, `Mayor ${m.surname} under fire`, `Town hall in the hot seat`]), body: `${v(s, 'a', ['A contract went to a cousin.', 'A road was paved to one house only.', 'The mayor was seen where the mayor should not have been.', 'The town books show a gap.'])} Mayor ${nm(m)} calls it a misunderstanding. ${era(s.y).news[0]!.toUpperCase()}${era(s.y).news.slice(1)} is not so sure.`, people: [m.id], apply: () => rep(m, -0.35) };
    },
  },
  {
    id: 'town_project', cat: 'civic', roles: ['none'], variants: 3 * 3, weight: (s) => (s.w.mayor ? 1 : 0),
    make: (s) => {
      const m = s.w.people[s.w.mayor!]!;
      const proj = v(s, 'p', ['a new library', 'a bandstand in the plaza', 'a stretch of paved sidewalk', 'a swimming pool', 'a community garden', 'streetlights for the east side']);
      return { headline: `Town gets ${proj}`, body: `Mayor ${nm(m)} cut the ribbon on ${proj} today. ${v(s, 'r', ['The crowd was bigger than expected.', 'Children got there first.', 'There was cake.'])}`, people: [m.id], apply: () => rep(m, 0.15) };
    },
  },
  {
    id: 'festival', cat: 'celebration', roles: ['person'], variants: 3 * 4, weight: () => 1.2,
    make: (s) => {
      const a = choose(s, 'a', s.adults.filter((p) => p.traits.charm > 0.5));
      if (!a) return null;
      const fest = v(s, 'f', ['harvest fair', 'summer picnic', 'Founders Day', 'bake-off', 'chili cook-off', 'lantern walk']);
      return { headline: v(s, 'h', [`${a.first} ${a.surname} runs a ${fest} to remember`, `The ${fest}: a triumph for ${a.first}`, `Everyone at the ${fest}`]), body: `The ${fest} drew the whole town thanks to ${nm(a)}. ${v(s, 'r', ['The pie table was empty by noon.', 'There was dancing until the lamps burned low.', 'A raffle raised money for the school.'])}`, people: [a.id], apply: () => rep(a, 0.18) };
    },
  },
  {
    id: 'anniversary', cat: 'celebration', roles: ['none'], variants: 3, weight: (s) => ((s.y - foundedOf(s.w.seed)) % 25 === 0 ? 40 : 0),
    make: (s) => {
      const age = s.y - foundedOf(s.w.seed);
      const m = s.w.mayor ? s.w.people[s.w.mayor] : undefined;
      return { headline: `The town turns ${age}`, body: `Flags on every porch: it has been ${age} years since the town was founded. ${m ? `Mayor ${nm(m)} gave a speech that ran long.` : 'Nobody could agree who should give the speech.'} The oldest resident cut the cake.`, people: m ? [m.id] : [], apply: () => { if (m) rep(m, 0.12); } };
    },
  },
  // ===== nature =====
  {
    id: 'storm', cat: 'nature', roles: ['none'], variants: 4 * 2, weight: () => 0.9,
    make: (s) => {
      const kind = v(s, 'k', ['A thunderstorm', 'A freak hailstorm', 'A windstorm', 'An ice storm']);
      const hit = s.shops.filter((b) => rand(s.w.seed, s.y, 'storm', b.id) < 0.4);
      return { headline: `${kind} batters the town`, body: `${kind} tore through overnight. ${hit.length ? `${hit.slice(0, 2).map((b) => b.name).join(' and ')} ${hit.length > 2 ? 'and others ' : ''}took the worst of it.` : 'Mostly branches down and porches soaked.'}`, people: [], apply: () => { for (const b of hit) b.capital *= 0.85; } };
    },
  },
  {
    id: 'flood', cat: 'nature', roles: ['none'], variants: 3, weight: (s) => (s.type === 'harbor' || s.type === 'mill' || s.type === 'resort' ? 0.8 : 0.1),
    make: (s) => {
      const hit = s.shops.filter((b) => rand(s.w.seed, s.y, 'flood', b.id) < 0.5);
      return { headline: `River over its banks`, body: `After days of rain the water came up Main Street. ${hit.length} shops were knee-deep. ${v(s, 'r', ['Neighbors formed a sandbag line.', 'The mayor walked the street in rubber boots.', 'Children paddled a washtub down the block.'])}`, people: [], apply: () => { for (const b of hit) b.capital *= 0.8; for (const p of s.adults) if (rand(s.w.seed, s.y, 'flood-home', p.id) < 0.1) p.wealth *= 0.9; } };
    },
  },
  {
    id: 'blight', cat: 'nature', roles: ['none'], variants: 3, weight: (s) => (s.type === 'farm' ? 1 : 0.05),
    make: (s) => ({ headline: `A hard season for the farms`, body: `${v(s, 'a', ['Drought', 'Early frost', 'A wet spring and a blight'])} cut the harvest. ${v(s, 'r', ['Prices at the feed store are up.', 'The bank is being patient. For now.', 'Everyone is tightening their belts.'])}`, people: [], apply: () => { for (const p of s.adults) p.wealth *= 0.93; } }),
  },
  {
    id: 'blizzard', cat: 'nature', roles: ['none'], variants: 3, weight: () => 0.6,
    make: (s) => ({ headline: v(s, 'h', ['Snowed in', 'Three days of white', 'The plow that never came', 'Town digs out']), body: `The big storm closed the roads for three days. ${v(s, 'a', ['Neighbors dug each other out.', 'The diner served anyone who could walk to it.', 'The only plow in town was the mayor’s own.'])}`, people: [], apply: () => { const a = s.adults; for (let i = 0; i + 1 < a.length; i += 2) if (rand(s.w.seed, s.y, 'snow', a[i]!.id) < 0.25) bump(s.w, a[i]!.id, a[i + 1]!.id, 0.05); } }),
  },
  {
    id: 'mine_accident', cat: 'tragedy', roles: ['pair'], variants: 3, weight: (s) => (s.type === 'mining' ? (s.y < 1985 ? 1.4 : 0.5) : 0),
    make: (s) => {
      const men = s.adults.filter((p) => p.sex === 'M' && ageIn(p, s.y) < 56);
      const a = choose(s, 'a', men), b = a && choose(s, 'b', men, [a.id]);
      if (!a) return null;
      const both = !!b && s.r('both') < 0.4;
      return { headline: `Mine accident claims ${both ? 'two' : 'one'}`, body: `A roof fall at the shaft took ${nm(a)}${both && b ? ` and ${nm(b)}` : ''}. ${v(s, 'r', ['The whistle blew all afternoon.', 'The whole town came to the pithead.', 'Rescuers worked through the night.'])}`, people: both && b ? [a.id, b.id] : [a.id], apply: () => { s.die(a, 'natural'); if (both && b) s.die(b, 'natural'); } };
    },
  },
  {
    id: 'epidemic', cat: 'tragedy', roles: ['none'], variants: 3, weight: (s) => (s.y <= 1957 ? 0.7 : s.y === 2020 ? 20 : 0.12),
    make: (s) => {
      const name = s.y <= 1957 ? 'Polio' : s.y === 2020 ? 'The pandemic' : 'A hard flu';
      const frail = s.adults.filter((p) => ageIn(p, s.y) >= 72 && rand(s.w.seed, s.y, 'flu', p.id) < (s.y === 2020 ? 0.16 : 0.07));
      return { headline: `${name} grips the town`, body: `${name} swept through this season. ${frail.length ? `We mourn ${frail.slice(0, 2).map(nm).join(' and ')}${frail.length > 2 ? ' and others' : ''}.` : 'The town came through with scares but no losses.'} ${v(s, 'r', ['School let out early.', 'Church was held on porches.', 'Neighbors left soup on doorsteps.'])}`, people: frail.slice(0, 3).map((p) => p.id), apply: () => { for (const p of frail) s.die(p, 'natural'); } };
    },
  },
  // ===== celebration =====
  {
    id: 'champions', cat: 'celebration', roles: ['person'], variants: 3, weight: (s) => (s.all.some((p) => ageIn(p, s.y) >= 14 && ageIn(p, s.y) < 18) ? 0.9 : 0),
    make: (s) => {
      const k = choose(s, 'k', s.all.filter((p) => ageIn(p, s.y) >= 14 && ageIn(p, s.y) < 18));
      if (!k) return null;
      return { headline: v(s, 'h', [`${k.first} ${k.surname} wins the day`, `Local hero: ${k.first} ${k.surname}`, `${k.first} brings home the prize`, `Pride of the town: ${k.surname}`]), body: `${nm(k)} (${ageIn(k, s.y)}) ${v(s, 'a', ['scored the winning run', 'took the state spelling title', 'won the county science fair', 'sank the last-second shot'])}. The town turned out to cheer.`, people: [k.id], apply: () => rep(k, 0.14) };
    },
  },
  {
    id: 'centenarian', cat: 'celebration', roles: ['person'], variants: 3, weight: (s) => (s.adults.some((p) => ageIn(p, s.y) >= 90) ? 1.3 : 0),
    make: (s) => {
      const a = choose(s, 'a', s.adults.filter((p) => ageIn(p, s.y) >= 90));
      if (!a) return null;
      return { headline: `${a.first} ${a.surname}, ${ageIn(a, s.y)} today`, body: `${nm(a)} ${v(s, 'a', ['blew out the candles with one breath', 'danced one slow dance', 'told the story of the town’s first winter again'])}. ${a.children.length ? `${a.children.length} children and a fair few grandchildren came to see it.` : 'Half the town came instead.'}`, people: [a.id], apply: () => rep(a, 0.06) };
    },
  },
  // ===== tragedy =====
  {
    id: 'car_accident', cat: 'tragedy', roles: ['pair'], variants: 3 * 3, weight: () => 0.5,
    make: (s) => {
      const a = choose(s, 'a', s.adults), b = a && choose(s, 'b', s.adults, [a.id]);
      if (!a) return null;
      const o = s.r('o');
      const where = v(s, 'w', ['at the crossroads', 'on the river road', 'near the railroad crossing']);
      if (o < 0.7) return { headline: `Crash ${where}`, body: `${nm(a)} in ${era(s.y).car} and ${b ? nm(b) : 'a delivery truck'} collided ${where}. Both walked away, though ${v(s, 'r', ['the fenders did not', 'the car is a total loss', 'tempers were short'])}.`, people: b ? [a.id, b.id] : [a.id], apply: () => { a.wealth *= 0.92; if (b) bump(s.w, a.id, b.id, -0.15); } };
      return { headline: `Tragedy ${where}`, body: `${nm(a)} was killed in a collision ${where}${b ? ` involving ${nm(b)}` : ''}. ${v(s, 'r', ['Flags flew at half staff.', 'The church could not hold everyone.'])}`, people: b ? [a.id, b.id] : [a.id], apply: () => { s.die(a, 'natural'); if (b) rep(b, -0.1); } };
    },
  },
  {
    id: 'house_fire', cat: 'tragedy', roles: ['person'], variants: 3, weight: () => 0.5,
    make: (s) => {
      const a = choose(s, 'a', s.adults);
      if (!a) return null;
      return { headline: `${a.surname} home lost to fire`, body: `The ${a.surname} house burned ${v(s, 'a', ['to the foundation', 'in the small hours', 'before the engine could arrive'])}. The family is safe. ${v(s, 'r', ['Neighbors took them in.', 'The church is collecting clothes.', 'A fund has been started at the bank.'])}`, people: [a.id], apply: () => { a.wealth *= 0.5; rep(a, 0.04); } };
    },
  },
  {
    id: 'benefit_fund', cat: 'family', roles: ['person'], variants: 3, weight: (s) => (s.all.some((p) => p.deathYear === undefined) && Object.values(s.w.people).some((p) => p.deathYear === s.y - 1 && ageIn(p, s.y - 1) >= 25 && ageIn(p, s.y - 1) <= 60 && p.children.length) ? 3 : 0),
    make: (s) => {
      const dead = Object.values(s.w.people).filter((p) => p.deathYear === s.y - 1 && ageIn(p, s.y - 1) >= 25 && ageIn(p, s.y - 1) <= 60 && p.children.length).sort((a, b) => (a.id < b.id ? -1 : 1));
      const d = choose(s, 'd', dead);
      const kid = d && s.w.people[d.children[0]!];
      const org = choose(s, 'o', s.adults.filter((p) => p.traits.charm > 0.4));
      if (!d || !kid || !org) return null;
      return { headline: `Town rallies for the ${d.surname}s`, body: `After ${nm(d)}'s death last year, ${nm(org)} organized a benefit supper for the family. ${v(s, 'r', ['Every seat was taken.', 'The line went around the block.', 'Even people who barely knew them came.'])}`, people: [org.id, d.id], apply: () => { kid.wealth += 40; rep(org, 0.15); } };
    },
  },
  // ===== arrivals =====
  {
    id: 'returning_native', cat: 'arrival', roles: ['none'], variants: 4 * 3, weight: (s) => (s.adults.length < 400 ? 0.7 : 0),
    make: (s) => {
      const fam = choose(s, 'f', s.adults);
      if (!fam) return null;
      const id = `ret:${s.y}:${s.slot}`;
      const age = 32 + Math.floor(s.r('age') * 25);
      const p = makePerson(s.w.seed, id, s.y - age, fam.surname);
      p.wealth = 80 + Math.floor(s.r('wealth') * 220);
      return { headline: v(s, 'h', [`${p.first} ${p.surname} comes home`, `A ${p.surname} returns`, `Prodigal ${p.first}`]), body: `After ${v(s, 'y', ['twenty years', 'a lifetime', 'half a lifetime'])} ${v(s, 'a', ['at sea', 'in the city', 'abroad', 'on the railroads'])}, ${nm(p)} has returned to the family name, ${v(s, 'm', ['with money in a trunk and stories nobody has heard', 'with a quiet air and a fat wallet', 'a stranger to half the town'])}. Is it kin to ${nm(fam)}? ${nm(fam)} says it is.`, people: [p.id, fam.id], apply: () => { s.w.people[id] = p; rep(p, 0.05); bump(s.w, p.id, fam.id, 0.35); } };
    },
  },
  {
    id: 'mystery_stranger', cat: 'arrival', roles: ['none'], variants: 4, weight: () => 0.5,
    make: (s) => {
      const id = `str:${s.y}:${s.slot}`;
      const age = 28 + Math.floor(s.r('age') * 30);
      const p = makePerson(s.w.seed, id, s.y - age, v(s, 'sn', ['Marlowe', 'Vance', 'Quill', 'Ashby', 'Rourke', 'Calder']));
      p.wealth = 20 + Math.floor(s.r('wealth') * 60);
      return { headline: v(s, 'h', ['A stranger takes the room above the diner', 'Who is the new arrival?', 'A newcomer nobody can place', 'Strange face at the boarding house']), body: `${nm(p)} arrived with one suitcase and ${v(s, 'a', ['a habit of watching the door', 'no references', 'a map with pins in it', 'an accent nobody could place'])}. ${hisHer(p)[0]!.toUpperCase()}${hisHer(p).slice(1)} past is an open question around ${era(s.y).gather}.`, people: [p.id], open: true, apply: () => { s.w.people[id] = p; rep(p, -0.05); } };
    },
  },
  {
    id: 'celebrity_visit', cat: 'arrival', roles: ['none'], variants: 4, weight: () => 0.35,
    make: (s) => ({ headline: `A famous face on Main Street`, body: `${v(s, 'a', ['A radio singer', 'A film actor passing through', 'A baseball star', 'A senator on a whistle-stop tour', 'A race-car driver'])} stopped in town and ${v(s, 'b', ['ate at the diner', 'signed autographs outside the bank', 'bought something in nearly every shop'])}. ${v(s, 'r', ['Shopkeepers say it was the best day of the year.', 'Nobody will let the diner forget it.'])}`, people: [], apply: () => { for (const b of s.shops) b.capital *= 1.08; } }),
  },
  // ===== family =====
  {
    id: 'inheritance_dispute', cat: 'family', roles: ['pair'], variants: 3, weight: (s) => (estates(s).length ? 2.2 : 0),
    make: (s) => {
      const dead = estates(s);
      const d = choose(s, 'd', dead);
      if (!d) return null;
      const kids = d.children.map((id) => s.w.people[id]!).filter(isAlive);
      const a = kids[0]!, b = kids[1]!;
      return { headline: `The ${d.surname} will: trouble in the family`, body: `${nm(a)} and ${nm(b)} cannot agree over ${nm(d)}'s ${v(s, 'a', ['house', 'savings', 'land', 'shop'])}. ${v(s, 'r', ['The lawyer has asked everyone to stay calm.', 'They no longer sit together at church.', 'A cousin has taken a side.'])}`, people: [a.id, b.id], open: true, apply: () => { bump(s.w, a.id, b.id, -0.45); if (a.wealth > b.wealth) { const t = a.wealth * 0.1; a.wealth -= t; b.wealth += t; } } };
    },
  },
  {
    id: 'reunion', cat: 'family', roles: ['pair'], variants: 3, weight: (s) => (s.adults.length > 6 ? 0.6 : 0),
    make: (s) => {
      const a = choose(s, 'a', s.adults.filter((p) => p.children.length >= 1 || p.parents.length >= 1));
      if (!a) return null;
      const kin = [...a.parents, ...a.children].map((id) => s.w.people[id]).filter((p): p is Person => !!p && isAlive(p) && affinity(s.w, a.id, p.id) < 0.35);
      const b = kin[0];
      if (!b) return null;
      return { headline: `${a.surname}s make peace`, body: `${nm(a)} and ${nm(b)} had not spoken for years. ${v(s, 'r', ['A funeral brought them together.', `${era(s.y).call[0]!.toUpperCase()}${era(s.y).call.slice(1)} on a holiday did it.`, 'A hospital waiting room did it.'])}`, people: [a.id, b.id], apply: () => bump(s.w, a.id, b.id, 0.5) };
    },
  },
  // ===== uncanny =====
  {
    id: 'strange_lights', cat: 'uncanny', roles: ['none'], variants: 4, weight: () => 0.4,
    make: (s) => ({ headline: `Strange lights over ${v(s, 'a', ['the old quarry', 'the lake', 'the north field', 'the water tower'])}`, body: `Three people swear they saw them: ${v(s, 'b', ['pale green, silent, moving against the wind', 'a ring of white lights, hovering', 'one great red light that dimmed and went out'])}. ${era(s.y).news[0]!.toUpperCase()}${era(s.y).news.slice(1)} calls it swamp gas. Nobody believes that.`, people: [] }),
  },
  {
    id: 'hero_dog', cat: 'uncanny', roles: ['person'], variants: 3, weight: () => 0.5,
    make: (s) => {
      const a = choose(s, 'a', s.all.filter((p) => ageIn(p, s.y) < 14));
      if (!a) return null;
      return { headline: `${a.first}'s dog saves the day`, body: `${a.first} ${a.surname}'s ${v(s, 'd', ['collie', 'beagle', 'old retriever'])} ${v(s, 'a', ['pulled a child out of the creek', 'barked until help came to a fallen neighbor', 'chased off a thief'])}. The dog is now the most famous resident on the street.`, people: [a.id], apply: () => rep(a, 0.08) };
    },
  },
];

// ---------- open threads and their sequels ----------

interface Sequel { from: string; delay: number; weight: number; make: (s: S, old: Story) => Made | null }
const SEQUELS: Sequel[] = [
  { from: 'missing_person', delay: 1, weight: 1, make: (s, old) => {
    const a = s.w.people[old.people[0]!]; if (!a) return null;
    if (isAlive(a) && s.r('found') < 0.55) return { headline: `${nm(a)} found alive`, body: `After ${s.y - old.year === 0 ? 'weeks' : `${s.y - old.year} years`} of silence ${nm(a)} turned up ${v(s, 'w', ['two counties over, with a new name', 'in a hospital, remembering nothing', 'at a cousin’s farm, unwilling to say why'])}. ${v(s, 'e', ['Nobody has the whole story.', `${a.first} says it is a private matter.`])}`, people: [a.id], sequelOf: old.id };
    return { headline: `Still no word of ${a.first} ${a.surname}`, body: `It has been ${s.y - old.year} years since ${nm(a)} vanished. Each year the church bell is rung once. ${v(s, 'e', ['Someone leaves flowers by the river.', 'The porch light is still on.'])}`, people: [a.id], sequelOf: old.id };
  } },
  { from: 'murder_mystery', delay: 1, weight: 1, make: (s, old) => {
    const vic = s.w.people[old.people[0]!], cul = s.w.people[old.people[1]!]; if (!vic || !cul) return null;
    if (isAlive(cul) && s.r('solved') < 0.6) return { headline: `Arrest in the ${vic.surname} case`, body: `${nm(cul)} was taken in over the death of ${nm(vic)}. ${v(s, 'e', ['A button found at the scene gave it away.', 'A neighbor finally came forward.', 'A letter was the missing piece.'])} The town is stunned.`, people: [cul.id, vic.id], sequelOf: old.id, apply: () => { rep(cul, -1); cul.wealth = Math.max(0, cul.wealth * 0.4); } };
    return { headline: `${vic.surname} case grows cold`, body: `The sheriff admits there are no new leads in the death of ${nm(vic)}. The file stays on the desk, and so does the town's unease.`, people: [vic.id], sequelOf: old.id };
  } },
  { from: 'robbery', delay: 1, weight: 1, make: (s, old) => {
    const sus = old.people[1] ? s.w.people[old.people[1]!] : undefined; if (!sus) return null;
    const guilty = s.r('guilty') < 0.5;
    return { headline: guilty ? `${nm(sus)} confesses` : `${nm(sus)} cleared`, body: guilty ? `${nm(sus)} admitted to the break-in. ${v(s, 'e', ['Cried in the sheriff’s office.', 'Paid some of it back already.'])}` : `A witness put ${nm(sus)} elsewhere that night. The town owes ${nm(sus)} an apology; it may take a while.`, people: [sus.id], sequelOf: old.id, apply: () => rep(sus, guilty ? -0.2 : 0.3) };
  } },
  { from: 'fence_dispute', delay: 2, weight: 1, make: (s, old) => {
    const a = s.w.people[old.people[0]!], b = s.w.people[old.people[1]!]; if (!a || !b || !isAlive(a) || !isAlive(b)) return null;
    return { headline: `${a.surname}s and ${b.surname}s bury the hatchet`, body: `The old quarrel is over. ${v(s, 'r', ['A wedding invitation did it.', 'A sick child did it.', 'A shared storm shelter did it.', 'Plain exhaustion did it.'])}`, people: [a.id, b.id], sequelOf: old.id, apply: () => bump(s.w, a.id, b.id, 0.7) };
  } },
  { from: 'affair_whisper', delay: 1, weight: 1, make: (s, old) => {
    const a = s.w.people[old.people[0]!], b = s.w.people[old.people[1]!]; if (!a || !b || !isAlive(a) || !isAlive(b)) return null;
    const sp = a.spouse ? s.w.people[a.spouse] : undefined;
    return { headline: `The ${a.surname} affair is out in the open`, body: `${nm(a)} ${sp ? `has been confronted by ${nm(sp)}` : 'could not deny it any longer'}, and ${nm(b)} has been seen leaving town for a few days. ${v(s, 'e', ['Nobody at church knows where to look.', 'The diner went quiet when they walked in.'])}`, people: [a.id, b.id], sequelOf: old.id, apply: () => { rep(a, -0.2); rep(b, -0.15); if (sp) bump(s.w, a.id, sp.id, -0.5); } };
  } },
  { from: 'mystery_stranger', delay: 2, weight: 1, make: (s, old) => {
    const p = s.w.people[old.people[0]!]; if (!p || !isAlive(p)) return null;
    const good = s.r('good') < 0.5;
    return { headline: good ? `The stranger turns out to be a hero` : `The stranger was running from something`, body: good ? `${nm(p)} was a ${v(s, 'a', ['decorated veteran', 'retired detective', 'doctor who lost everything but would not stop helping'])}. The town is embarrassed by its suspicion.` : `${nm(p)} was on the run from ${v(s, 'a', ['a debt in another state', 'an old crime', 'a family that wanted nothing more to do with them'])}. Whether to forgive is the question of the season.`, people: [p.id], sequelOf: old.id, apply: () => rep(p, good ? 0.5 : -0.2) };
  } },
  { from: 'inheritance_dispute', delay: 2, weight: 1, make: (s, old) => {
    const a = s.w.people[old.people[0]!], b = s.w.people[old.people[1]!]; if (!a || !b || !isAlive(a) || !isAlive(b)) return null;
    const ok = s.r('ok') < 0.5;
    return { headline: ok ? `${a.surname} siblings settle` : `${a.surname} siblings stop speaking for good`, body: ok ? `The judge split the estate down the middle and so, at last, did the siblings.` : `The estate is settled and the family is not. ${nm(a)} and ${nm(b)} cross the street to avoid each other.`, people: [a.id, b.id], sequelOf: old.id, apply: () => bump(s.w, a.id, b.id, ok ? 0.6 : -0.3) };
  } },
];

// ---------- running a year ----------

export const TEMPLATE_COUNT = T.length;
export const SEQUEL_COUNT = SEQUELS.length;

function makeS(w: World, y: number, slot: number, die: S['die'], sorted: Person[]): S {
  const all = sorted.filter(isAlive);
  const adults = all.filter((p) => ageIn(p, y) >= 18);
  return { w, y, slot, type: archetypeOf(w.seed).id, die, r: (...k) => rand(w.seed, y, 'story', slot, ...k), all, adults, shops: open(w) };
}

/** Pick the template for this slot: weighted, by keyed rolls (stable when other things change). */
function pickTemplate(s: S, used: Set<string>): Template | undefined {
  let best: Template | undefined, bv = Infinity;
  for (const t of T) {
    // A kind of story does not come around again within a few years (the town would feel like a loop).
    const recent = (s.w.stories ?? []).some((x) => x.template === t.id && x.year > s.y - 4);
    const wgt = used.has(t.id) || (recent && t.id !== 'anniversary') ? 0 : t.weight(s);
    if (wgt <= 0) continue;
    const u = Math.max(1e-9, rand(s.w.seed, s.y, 'tpl', s.slot, t.id));
    const key = -Math.log(u) / wgt; // exponential race: smallest wins, with probability proportional to weight
    if (key < bv) { bv = key; best = t; }
  }
  return best;
}

/** One year of stories. Returns what was reported (also appended to w.stories). */
export function runScenarios(c: Ctx, die: S['die']): Story[] {
  const { w, y } = c;
  const out: Story[] = [];
  const sorted = Object.values(w.people).sort((a, b) => (a.id < b.id ? -1 : 1));
  const pop = sorted.filter(isAlive).length;
  const n = 1 + Math.min(3, Math.floor(pop / 60)) + (rand(w.seed, y, 'story-extra') < 0.4 ? 1 : 0);
  const used = new Set<string>();
  w.stories ??= [];
  // Follow up open threads first (one per year at most).
  const ready = w.stories.filter((st) => st.open && SEQUELS.some((q) => q.from === st.template && y - st.year >= q.delay));
  if (ready.length && rand(w.seed, y, 'sequel') < 0.5) {
    let pick: Story | undefined, bv = -1;
    for (const st of ready) { const k = rand(w.seed, y, 'sequel-pick', st.id); if (k > bv) { bv = k; pick = st; } }
    const q = pick && SEQUELS.find((x) => x.from === pick!.template);
    if (pick && q) {
      const s = makeS(w, y, 99, die, sorted);
      const made = q.make(s, pick);
      if (made) {
        pick.open = false;
        made.apply?.();
        const st: Story = { id: `${y}:99:${pick.template}+`, year: y, template: `${pick.template}+`, category: pick.category, headline: made.headline, body: made.body, people: made.people, sequelOf: pick.id, ...(made.business ? { business: made.business } : {}) };
        w.stories.push(st); out.push(st);
      }
    }
  }
  for (let slot = 0; slot < n; slot++) {
    const s = makeS(w, y, slot, die, sorted);
    const t = pickTemplate(s, used);
    if (!t) continue;
    const made = t.make(s);
    if (!made) continue;
    used.add(t.id);
    made.apply?.();
    const dup = w.stories.some((x) => x.headline === made.headline);
    const st: Story = { id: `${y}:${slot}:${t.id}`, year: y, template: t.id, category: t.cat, headline: dup ? `${v(s, 'again', ['Again: ', 'Once more: ', 'Here we go again: '])}${made.headline}` : made.headline, body: made.body, people: made.people, ...(made.business ? { business: made.business } : {}), ...(made.open ? { open: true } : {}) };
    w.stories.push(st); out.push(st);
  }
  return out;
}

/**
 * How many distinct stories this library can tell in this town right now: for each template, the ways to fill
 * its roles from the town's real people and shops, times its outcomes and phrasings. (Per year: multiply by the
 * years, and by every town in the world, which has its own people.)
 */
export function scenarioSpace(w: World, y = w.year): number {
  const adults = living(w).filter((p) => ageIn(p, y) >= 18).length;
  const shops = open(w).length;
  let total = 0;
  for (const t of T) {
    let ways = 1;
    for (const r of t.roles) ways *= r === 'person' ? Math.max(1, adults) : r === 'pair' ? Math.max(1, adults) * Math.max(1, adults - 1) : r === 'shop' ? Math.max(1, shops) : r === 'pairShop' ? Math.max(1, shops) * Math.max(1, shops - 1) : 1;
    total += ways * t.variants;
  }
  return total;
}
