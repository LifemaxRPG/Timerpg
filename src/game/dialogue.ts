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
    { id: 'praise', label: 'Say something nice about them around town', kind: 'act', enabled: fam >= 1, ...(fam < 1 ? { note: 'Get to know them first' } : {}) },
    { id: 'gift', label: 'Give a gift ($200)', kind: 'act', enabled: fam >= 1, ...(fam < 1 ? { note: 'Get to know them first' } : {}) },
    { id: 'smear', label: 'Spread a nasty rumor about them', kind: 'danger', enabled: fam >= 2, ...(fam < 2 ? { note: 'You barely know them' } : { note: c.witnesses ? 'People are listening' : 'Nobody is listening' }) },
    { id: 'drastic', label: 'Do something drastic…', kind: 'danger', enabled: fam >= 2, ...(fam < 2 ? { note: 'You barely know them' } : { note: c.witnesses ? `${c.witnesses} ${c.witnesses === 1 ? 'person' : 'people'} could see` : 'No one is around' }) },
  ];
  return opts;
}

export const specFor = (id: string): TalkSpec | null =>
  id === 'praise' ? { kind: 'rumor', amount: 1 } : id === 'smear' ? { kind: 'rumor', amount: -1 } : id === 'gift' ? { kind: 'gift', amount: 200 } : id === 'drastic' ? { kind: 'kill' } : null;

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
    default: return '...';
  }
}
