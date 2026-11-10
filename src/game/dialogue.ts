import { heardBy, secretsOf, trustNeeded } from '../sim/knowledge';
import { ageIn, fullName, type Person, type World } from '../sim/types';

// WALK-UP CONVERSATION. What you can say or do to a person depends on how well they know you,
// who is watching, and whether the law is after you. The words come from `narrate`, which for now
// fills templates from the simulation's facts; a local language model can later voice the same
// fact sheet (it must never decide outcomes: `optionsFor` and the sim do that).

export interface TalkCtx {
  w: World;
  person: Person;
  job: string;
  townName: string;
  /** How many times you have chatted with them (0 = strangers). */
  familiarity: number;
  /** Other people close enough to see what you do right now. */
  witnesses: number;
  wanted: boolean;
  /** What the player has already learned (their notebook). */
  journal: JournalEntry[];
}

export interface JournalEntry {
  /** Secret id (seed:person:kind). */
  id: string;
  about: string;
  aboutName: string;
  text: string;
  /** Who told you and when. */
  from: string;
  year: number;
}

export interface Option {
  id: string;
  label: string;
  kind: 'say' | 'act' | 'danger';
  enabled: boolean;
  /** Why it is unavailable, or a warning for risky options. */
  note?: string;
}

export type TalkSpec =
  | { kind: 'rumor'; amount: number }
  | { kind: 'gift'; amount: number }
  | { kind: 'kill' };

const pick = <T,>(a: T[], seed: number): T => a[Math.floor(seed * a.length) % a.length]!;
const seedOf = (p: Person): number => (parseInt(p.id.replace(/\D/g, '') || '7', 10) * 0.6180339) % 1;

export function optionsFor(c: TalkCtx): Option[] {
  if (c.wanted) return [];
  const fam = c.familiarity;
  const opts: Option[] = [
    { id: 'chat', label: 'Chat', kind: 'say', enabled: true },
    { id: 'town', label: `Ask about ${c.townName}`, kind: 'say', enabled: true },
    { id: 'family', label: 'Ask about their family', kind: 'say', enabled: true },
    { id: 'work', label: 'Ask about their work', kind: 'say', enabled: true },
    { id: 'rumors', label: 'Ask what they\'ve heard around town', kind: 'say', enabled: fam >= 2, ...(fam < 2 ? { note: 'They don\'t know you well enough' } : {}) },
    { id: 'secret', label: 'Ask if they\'re keeping anything to themselves', kind: 'say', enabled: fam >= 3, ...(fam < 3 ? { note: 'They don\'t trust you yet' } : {}) },
    { id: 'praise', label: 'Say something nice about them around town', kind: 'act', enabled: fam >= 1, ...(fam < 1 ? { note: 'Get to know them first' } : {}) },
    { id: 'gift', label: 'Give a gift ($200)', kind: 'act', enabled: fam >= 1, ...(fam < 1 ? { note: 'Get to know them first' } : {}) },
    { id: 'smear', label: 'Spread a nasty rumor about them', kind: 'danger', enabled: fam >= 2, ...(fam < 2 ? { note: 'You barely know them' } : { note: c.witnesses ? 'People are listening' : 'Nobody is listening' }) },
    { id: 'drastic', label: 'Do something drastic…', kind: 'danger', enabled: fam >= 2, ...(fam < 2 ? { note: 'You barely know them' } : { note: c.witnesses ? `${c.witnesses} ${c.witnesses === 1 ? 'person' : 'people'} could see` : 'No one is around' }) },
  ];
  // Let slip something you learned: it hurts the person it is about.
  for (const j of c.journal.filter((e) => e.about !== c.person.id).slice(-3)) {
    opts.push({ id: `leak:${j.id}`, label: `Let it slip that ${j.aboutName} ${j.text}`, kind: 'danger', enabled: fam >= 2, note: c.witnesses ? 'People are listening' : 'Nobody is listening' });
  }
  return opts;
}

/** Ask what they have heard (about others) or what they are hiding (about themselves). */
export function ask(c: TalkCtx, intent: 'rumors' | 'secret'): { line: string; entry: JournalEntry | null } {
  const own = intent === 'secret';
  const pool = own ? secretsOf(c.w, c.person) : heardBy(c.w, c.person);
  const fresh = pool.filter((sc) => !c.journal.some((j) => j.id === sc.id));
  if (!pool.length) return { line: own ? 'Nothing worth telling. I live a quiet life.' : 'Nothing I\'d repeat. People here keep to themselves.', entry: null };
  if (!fresh.length) return { line: own ? 'You already know everything I\'m willing to say.' : 'That\'s all I\'ve heard, honestly.', entry: null };
  const sc = fresh[0]!;
  if (c.familiarity < trustNeeded(c.person, sc, own)) {
    return { line: own ? 'Some things I keep to myself. Not yet.' : 'I hear things, sure. But I don\'t know you well enough to say.', entry: null };
  }
  const about = c.w.people[sc.about]!;
  const name = own ? 'I' : fullName(about);
  const entry: JournalEntry = { id: sc.id, about: sc.about, aboutName: fullName(about), text: sc.text, from: fullName(c.person), year: c.w.year };
  return { line: own ? `...Fine. Between us: I ${sc.text.replace(/^is /, 'am ').replace(/^holds /, 'hold ').replace(/^wants /, 'want ').replace(/^dreams /, 'dream ').replace(/^ran /, 'ran ')}. Don\'t make me regret telling you.` : `Keep this quiet, but ${name} ${sc.text}.`, entry };
}

export const specFor = (id: string): TalkSpec | null =>
  id === 'praise' ? { kind: 'rumor', amount: 1 } : id === 'smear' ? { kind: 'rumor', amount: -1 } : id === 'gift' ? { kind: 'gift', amount: 200 } : id === 'drastic' ? { kind: 'kill' } : id.startsWith('leak:') ? { kind: 'rumor', amount: -1 } : null;

/** A line of dialogue for an intent, built only from facts in the simulation. */
export function narrate(c: TalkCtx, intent: string): string {
  const p = c.person, w = c.w, s = seedOf(p);
  const warm = p.traits.charm > 0.55;
  const spouse = p.spouse ? w.people[p.spouse] : undefined;
  const kids = p.children.map((id) => w.people[id]).filter((k): k is Person => !!k && k.deathYear === undefined);
  const mayor = w.mayor ? w.people[w.mayor] : undefined;
  switch (intent) {
    case 'greet':
      if (c.wanted) return pick(['Stay away from me.', 'I know what you did. Leave.'], s);
      return c.familiarity === 0
        ? pick(['Hello there. Haven\'t seen you around.', 'Morning. You new in town?', 'Oh — hello.'], s)
        : warm ? pick(['Good to see you again!', 'Well, look who it is.'], s) : pick(['Oh. You again.', 'Mm. Hello.'], s);
    case 'chat':
      return warm ? pick(['Lovely day for it, isn\'t it?', 'Can\'t complain. Keeping busy.', 'Nice to have someone to talk to.'], s)
        : pick(['Can\'t talk long.', 'Things are what they are.', 'Is there something you need?'], s);
    case 'town':
      return `${c.townName}? It's home. ${mayor ? `${fullName(mayor)} is mayor${mayor.reputation < -0.2 ? ', though not everyone is happy about it' : mayor.reputation > 0.3 ? ', and people like them' : ''}.` : 'We have no mayor right now.'} ${Object.keys(w.people).length > 150 ? 'It\'s getting crowded.' : 'It\'s still small enough that everyone knows everyone.'}`;
    case 'family': {
      const bits: string[] = [];
      if (spouse && spouse.deathYear === undefined) bits.push(`I'm married to ${fullName(spouse)}.`);
      else if (p.spouse) bits.push('I lost my spouse.');
      if (kids.length) bits.push(`${kids.length === 1 ? 'One child' : `${kids.length} children`}: ${kids.slice(0, 3).map((k) => k.first).join(', ')}.`);
      return bits.length ? bits.join(' ') : ageIn(p, w.year) < 18 ? 'I live with my folks.' : 'It\'s just me.';
    }
    case 'work':
      return c.job.includes('owner') ? `I run ${c.job.replace(' (owner)', '')}. ${p.wealth > 5000 ? 'Business is good.' : 'It keeps the lights on.'}`
        : c.job === 'retired' ? 'Retired. I sit in the plaza and watch the world go by.'
        : c.job === 'at school' ? 'I\'m at school.' : `I ${c.job}.`;
    case 'praise': return 'You said what about me? ...Well. That\'s kind of you.';
    case 'gift': return warm ? 'For me? Thank you, truly.' : 'Oh. ...Thank you.';
    default:
      if (intent.startsWith('leak:')) return warm ? 'Oh! Is that so? Well... I\'ll keep my ears open.' : 'Hm. I hadn\'t heard that. Interesting.';
      return '...';
  }
}
