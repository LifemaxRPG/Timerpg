import type {
  LoopMemoryEvent,
  LoopMemoryEventKind,
  LoopMemoryHearer,
  LoopMemoryWitness,
} from '../loop/types';

// FIRST-PERSON MEMORIES FROM LOOP EVENTS (G021).
// Pure, deterministic, plain-JSON transformation from loop events into 0..6 grounded first-person
// sentences for an NPC's fact sheet. Every returned memory traces 1-to-1 to a real `event.id`,
// preserves epistemic distance (saw it > heard from a friend > heard around town), fades with
// time, and shades wording by the speaker's traits (loyal friend plays it down, gossip plays it up).

export const MAX_MEMORIES = 6;

export type MemorySource = 'seen' | 'direct' | 'heard_friend' | 'heard_rumor';
export type MemoryTone = 'neutral' | 'downplayed' | 'embellished';

export type MemoryNow =
  | number
  | {
      day: number;
      hour?: number;
      year?: number;
      dayName?: string;
    };

export interface MemoryPersonTraits {
  charm?: number;
  ambition?: number;
  diligence?: number;
  /** 0..1 loyalty toward friends / the player (high loyalty downplays accusations). */
  loyalty?: number;
  /** 0..1 tendency to gossip and play up stories (never turns heard into seen). */
  gossip?: number;
  /** Relationship to the player (e.g. 'friend' or 'family' softens negative events). */
  relation?: 'friend' | 'family' | 'coworker' | 'acquaintance' | 'stranger';
}

export interface MemoryOptions {
  /** Traits of the NPC whose fact sheet is being built (compatible with `Person['traits']`). */
  traits?: MemoryPersonTraits;
  /** Explicit relationship of this NPC to the player (overrides `traits.relation`). */
  relationToPlayer?: 'friend' | 'family' | 'coworker' | 'acquaintance' | 'stranger';
  /** Person IDs this NPC considers friends (promotes hearer attribution to `heard_friend`). */
  friendIds?: string[];
  /** Optional known player name (used when the NPC worked with or befriended the player). */
  playerName?: string;
  /** Maximum lines to return (clamped to 1..6; defaults to 6). */
  max?: number;
  /** Minimum relevance threshold below which heavily faded memories drop off (default 0.1). */
  minRelevance?: number;
}

export interface MemoryLine {
  /** Exact `id` of the source `LoopMemoryEvent`. Never invented. */
  eventId: string;
  /** Kind of the source loop event. */
  kind: LoopMemoryEventKind;
  /** Plain-English first-person memory line ready for the fact sheet. */
  line: string;
  /** Epistemic source tier: `seen`/`direct` > `heard_friend` > `heard_rumor`. */
  source: MemorySource;
  /** True iff the NPC only heard about this event and never saw or participated in it. */
  heardOnly: boolean;
  /** Name of who told the NPC (when heard from a named person). */
  toldBy?: string;
  /** 0..1 certainty after source tier and time fading. */
  certainty: number;
  /** 0..1 relevance score used for ranking (higher = more prominent on the sheet). */
  relevance: number;
  /** Elapsed days between the event (or hearing it) and `now`. */
  ageDays: number;
  /** How the NPC's traits shaded the line. */
  tone: MemoryTone;
  /** Grounded lowercase topic cues from the source event (used by `validate()`). */
  cues: string[];
}

const STOP_WORDS = new Set([
  'a', 'an', 'the', 'of', 'to', 'in', 'on', 'at', 'by', 'for', 'from', 'with', 'and', 'or', 'but',
  'is', 'are', 'was', 'were', 'be', 'been', 'you', 'your', 'me', 'my', 'i', 'it', 'its', 'that',
  'this', 'some', 'someone', 'out', 'up', 'down', 'into', 'over', 'about', 'after', 'before',
]);

function clamp(n: number, lo: number, hi: number): number {
  return n < lo ? lo : n > hi ? hi : n;
}

function round3(n: number): number {
  return Math.round(n * 1000) / 1000;
}

function resolveNowDay(now: MemoryNow): number {
  return typeof now === 'number' ? now : now.day;
}

function defaultCrimeSeverity(crimeKind?: string): number {
  switch ((crimeKind ?? '').toLowerCase()) {
    case 'murder':
    case 'homicide':
      return 1.0;
    case 'arson':
      return 0.92;
    case 'robbery':
    case 'assault':
      return 0.82;
    case 'burglary':
    case 'car_theft':
      return 0.72;
    case 'bribery':
    case 'intimidation':
    case 'perjury':
      return 0.7;
    case 'theft':
    case 'pickpocket':
    case 'shoplifting':
      return 0.55;
    case 'trespass':
      return 0.38;
    default:
      return 0.58;
  }
}

function isNegativeEvent(e: LoopMemoryEvent): boolean {
  if (e.kind === 'crime' || e.kind === 'arrest' || e.kind === 'stop' || e.kind === 'rumor') {
    return true;
  }
  if (e.kind === 'trial') {
    return e.verdict !== 'not_guilty';
  }
  if (e.kind === 'purchase') {
    return Boolean(e.hot);
  }
  return false;
}

function resolveTone(e: LoopMemoryEvent, options?: MemoryOptions): MemoryTone {
  const traits = options?.traits;
  const rel = options?.relationToPlayer ?? traits?.relation;
  const isLoyal =
    rel === 'friend' ||
    rel === 'family' ||
    (traits?.loyalty !== undefined && traits.loyalty >= 0.7);
  const isGossip =
    (traits?.gossip !== undefined && traits.gossip >= 0.65) ||
    (traits?.charm !== undefined &&
      traits.charm >= 0.7 &&
      (traits.diligence ?? 0.5) < 0.45);

  // Loyal friendship protects the player from harsh framing on negative events/rumors.
  if (isNegativeEvent(e)) {
    if (isLoyal) return 'downplayed';
    if (isGossip) return 'embellished';
  } else if (isGossip && (e.kind === 'kindness' || e.kind === 'work_shift')) {
    return 'embellished';
  }
  return 'neutral';
}

function timePhrase(ageDays: number, dayName?: string): string {
  if (ageDays <= 0) return 'earlier today';
  if (ageDays === 1) return 'yesterday';
  if (ageDays <= 6 && dayName && dayName.trim()) return `on ${dayName.trim()}`;
  if (ageDays <= 10) return 'the other day';
  if (ageDays <= 28) return 'a couple of weeks ago';
  if (ageDays <= 75) return 'a while back';
  return 'some time ago';
}

function cleanText(s?: string): string | undefined {
  if (!s) return undefined;
  const trimmed = s.replace(/\s+/g, ' ').trim();
  return trimmed.length > 0 ? trimmed : undefined;
}

function withArticle(label: string): string {
  if (/^(a|an|the|some|my|their|his|her|our)\b/i.test(label)) return label;
  return /^[aeiou]/i.test(label) ? `an ${label}` : `a ${label}`;
}

function firstNameOf(full?: string): string | undefined {
  const clean = cleanText(full);
  if (!clean) return undefined;
  return clean.split(' ')[0];
}

function extractTokens(text?: string): string[] {
  if (!text) return [];
  return text
    .toLowerCase()
    .replace(/[^a-z0-9'\s]+/g, ' ')
    .split(/\s+/)
    .map((w) => w.replace(/^'+|'+$/g, '').replace(/'s$/, ''))
    .filter((w) => w.length >= 3 && !STOP_WORDS.has(w));
}

function buildCues(e: LoopMemoryEvent, toldBy?: string): string[] {
  const out = new Set<string>();
  const add = (text?: string) => {
    for (const tok of extractTokens(text)) out.add(tok);
  };
  add(e.placeName);
  add(e.itemLabel);
  add(e.crimeKind);
  add(e.rumorSummary);
  add(e.kindnessDetail);
  if (e.charges) for (const c of e.charges) add(c);

  // Kind-level grounded semantic cues (used for epistemic heard-vs-seen matching in validate()).
  if (e.kind === 'arrest') {
    out.add('arrest');
    out.add('arrested');
    out.add('police');
    out.add('picked');
    out.add('booked');
  } else if (e.kind === 'stop') {
    out.add('stop');
    out.add('stopped');
    out.add('police');
    out.add('questioned');
  } else if (e.kind === 'trial') {
    out.add('trial');
    out.add('court');
    out.add('courthouse');
    out.add('verdict');
    if (e.verdict) add(e.verdict.replace(/_/g, ' '));
  } else if (e.kind === 'crime') {
    const ck = (e.crimeKind ?? '').toLowerCase();
    if (ck === 'arson') {
      out.add('fire');
      out.add('burn');
      out.add('burned');
      out.add('arson');
    } else if (ck === 'shoplifting' || ck === 'theft' || ck === 'pickpocket' || ck === 'burglary') {
      out.add('stole');
      out.add('stolen');
      out.add('steal');
      out.add('took');
      out.add('take');
    } else if (ck === 'car_theft') {
      out.add('car');
      out.add('stole');
      out.add('stolen');
    } else if (ck === 'robbery') {
      out.add('robbed');
      out.add('robbery');
    } else if (ck === 'assault') {
      out.add('hit');
      out.add('attacked');
      out.add('assault');
    } else if (ck === 'trespass') {
      out.add('trespass');
      out.add('broke');
    }
  } else if (e.kind === 'work_shift') {
    out.add('shift');
    out.add('worked');
  } else if (e.kind === 'purchase') {
    out.add(e.sold ? 'sold' : 'bought');
    if (e.hot) out.add('stolen');
  } else if (e.kind === 'kindness') {
    out.add('helped');
    out.add('kind');
  }

  // Never include the teller's name as an event cue (mentioning Lois doesn't mean claiming to see the crime).
  if (toldBy) {
    for (const tok of extractTokens(toldBy)) out.delete(tok);
  }
  return Array.from(out).sort();
}

function crimeActionClause(e: LoopMemoryEvent, pastTense: boolean): string {
  const kind = (e.crimeKind ?? 'theft').toLowerCase();
  const item = cleanText(e.itemLabel);
  const place = cleanText(e.placeName);
  const fromPlace = place ? ` from ${place}` : '';
  const atPlace = place ? ` at ${place}` : '';

  if (kind === 'arson') {
    return place
      ? (pastTense ? `set fire to ${place}` : `set fire to ${place}`)
      : 'started a fire';
  }
  if (kind === 'shoplifting' || kind === 'theft' || kind === 'pickpocket') {
    const what = item ? withArticle(item) : 'something';
    return pastTense ? `took ${what}${fromPlace}` : `take ${what}${fromPlace}`;
  }
  if (kind === 'burglary') {
    if (item) {
      return pastTense
        ? `broke into ${place ?? 'a building'} and took ${withArticle(item)}`
        : `break into ${place ?? 'a building'} and take ${withArticle(item)}`;
    }
    return pastTense
      ? `broke into ${place ?? 'a building'}`
      : `break into ${place ?? 'a building'}`;
  }
  if (kind === 'car_theft') {
    const what = item ? withArticle(item) : 'a car';
    return pastTense ? `stole ${what}${fromPlace}` : `steal ${what}${fromPlace}`;
  }
  if (kind === 'robbery') {
    return pastTense ? `robbed ${place ?? 'someone'}` : `rob ${place ?? 'someone'}`;
  }
  if (kind === 'assault') {
    return pastTense ? `attacked someone${atPlace}` : `attack someone${atPlace}`;
  }
  if (kind === 'trespass') {
    return pastTense ? `sneaked into ${place ?? 'a closed place'}` : `sneak into ${place ?? 'a closed place'}`;
  }
  if (item) {
    return pastTense ? `took ${withArticle(item)}${fromPlace}` : `take ${withArticle(item)}${fromPlace}`;
  }
  return pastTense ? `caused trouble${atPlace}` : `cause trouble${atPlace}`;
}

function eventFactClause(e: LoopMemoryEvent): string {
  const place = cleanText(e.placeName);
  const atPlace = place ? ` at ${place}` : '';
  const item = cleanText(e.itemLabel);

  switch (e.kind) {
    case 'crime':
      return `you ${crimeActionClause(e, true)}`;
    case 'rumor': {
      const custom = cleanText(e.rumorSummary);
      if (custom) return custom.replace(/[.!?]+$/, '');
      if (e.crimeKind || item) return `you ${crimeActionClause(e, true)}`;
      return `you were in trouble${atPlace}`;
    }
    case 'arrest': {
      const charge = e.charges?.length ? ` over ${e.charges[0]!.replace(/_/g, ' ')}` : '';
      return `you were picked up by the police${atPlace}${charge}`;
    }
    case 'stop': {
      if (e.stopOutcome === 'arrested') {
        return `the police picked you up${atPlace}`;
      }
      return `the police stopped you${atPlace}`;
    }
    case 'trial': {
      const charge = e.charges?.length ? ` for ${e.charges[0]!.replace(/_/g, ' ')}` : '';
      if (e.verdict === 'guilty') return `the court found you guilty${charge}`;
      if (e.verdict === 'plea_guilty') return `you pleaded guilty in court${charge}`;
      if (e.verdict === 'not_guilty') return `the court cleared you${charge}`;
      if (e.verdict === 'missed_date') return `you skipped your court date${charge}`;
      return `you were on trial${atPlace}${charge}`;
    }
    case 'work_shift':
      return `you put in a shift${atPlace}`;
    case 'purchase':
      if (e.sold) {
        return `you sold ${item ? withArticle(item) : 'something'}${atPlace}`;
      }
      return `you bought ${item ? withArticle(item) : 'something'}${atPlace}`;
    case 'kindness': {
      const detail = cleanText(e.kindnessDetail);
      if (detail) return `you ${detail.replace(/^you\s+/i, '').replace(/[.!?]+$/, '')}${atPlace}`;
      return `you did someone a good turn${atPlace}`;
    }
  }
}

function formatDirectLine(
  personId: string,
  e: LoopMemoryEvent,
  ageDays: number,
  tone: MemoryTone,
  certainty: number,
  playerName?: string,
): string {
  const when = timePhrase(ageDays, e.dayName);
  const place = cleanText(e.placeName);
  const atPlace = place ? ` at ${place}` : '';
  const item = cleanText(e.itemLabel);
  const pFirst = firstNameOf(e.playerName ?? playerName);
  const fadedPrefix = certainty < 0.48 ? 'If I remember right, ' : '';

  if (e.kind === 'crime') {
    const clause = crimeActionClause(e, false);
    if (tone === 'downplayed') {
      return `${fadedPrefix}I saw you ${clause} ${when}, though I'm sure there was more to it.`;
    }
    if (tone === 'embellished') {
      return `${fadedPrefix}I saw you with my own eyes ${clause} ${when} — quite the scene.`;
    }
    return `${fadedPrefix}I saw you ${clause} ${when}.`;
  }

  if (e.kind === 'work_shift') {
    const who = pFirst ? `, ${pFirst}` : '';
    const isBoss = e.employerId === personId;
    if (isBoss) {
      return `You worked a shift for me${atPlace} ${when}${who}.`;
    }
    if (tone === 'embellished') {
      return `You worked a shift right alongside me${atPlace} ${when}${who} — good company too.`;
    }
    return `You worked a shift with me${atPlace} ${when}${who}.`;
  }

  if (e.kind === 'stop') {
    const isOfficer = e.officerId === personId;
    if (isOfficer) {
      const outcome =
        e.stopOutcome === 'warned'
          ? ' and gave you a warning'
          : e.stopOutcome === 'searched'
            ? ' and checked your pockets'
            : e.stopOutcome === 'arrested'
              ? ' and brought you in'
              : ' for a few questions';
      return `I stopped you${atPlace} ${when}${outcome}.`;
    }
    if (tone === 'downplayed') {
      return `${fadedPrefix}I saw the police stop you${atPlace} ${when}, though it looked like a misunderstanding.`;
    }
    if (tone === 'embellished') {
      return `${fadedPrefix}I saw the police corner you${atPlace} ${when} — everyone stared.`;
    }
    return `${fadedPrefix}I saw the police stop you${atPlace} ${when}.`;
  }

  if (e.kind === 'arrest') {
    const isOfficer = e.officerId === personId;
    const charge = e.charges?.length ? ` over ${e.charges[0]!.replace(/_/g, ' ')}` : '';
    if (isOfficer) {
      return `I arrested you${atPlace} ${when}${charge}.`;
    }
    if (tone === 'downplayed') {
      return `${fadedPrefix}I saw the police pick you up${atPlace} ${when}, though I hope they had it wrong.`;
    }
    if (tone === 'embellished') {
      return `${fadedPrefix}I saw the police haul you off${atPlace} ${when}${charge} — quite a commotion.`;
    }
    return `${fadedPrefix}I saw the police pick you up${atPlace} ${when}${charge}.`;
  }

  if (e.kind === 'trial') {
    const charge = e.charges?.length ? ` for ${e.charges[0]!.replace(/_/g, ' ')}` : '';
    if (e.verdict === 'not_guilty') {
      return `${fadedPrefix}I was at the courthouse ${when} when the jury cleared you${charge}.`;
    }
    if (e.verdict === 'plea_guilty') {
      return `${fadedPrefix}I was in court ${when} when you pleaded guilty${charge}.`;
    }
    if (e.verdict === 'missed_date') {
      return `${fadedPrefix}I was in court ${when} when you failed to show up${charge}.`;
    }
    if (tone === 'downplayed') {
      return `${fadedPrefix}I was in court ${when} for your trial${charge}, though it felt harsh.`;
    }
    if (tone === 'embellished') {
      return `${fadedPrefix}I was in court ${when} when they found you guilty${charge} — big talk of the town.`;
    }
    return `${fadedPrefix}I was in court ${when} when the jury found you guilty${charge}.`;
  }

  if (e.kind === 'purchase') {
    const what = item ? withArticle(item) : 'something';
    if (e.sold) {
      if (e.hot) {
        if (tone === 'downplayed') {
          return `You sold me ${what}${atPlace} ${when}, and I didn't ask questions.`;
        }
        return `You sold me ${what}${atPlace} ${when}, and it looked mighty suspicious.`;
      }
      return `You sold me ${what}${atPlace} ${when}.`;
    }
    return `You bought ${what} from me${atPlace} ${when}.`;
  }

  if (e.kind === 'kindness') {
    const detail = cleanText(e.kindnessDetail);
    const body = detail
      ? detail.replace(/^you\s+/i, '').replace(/[.!?]+$/, '')
      : 'did me a real kindness';
    const who = pFirst ? `, ${pFirst}` : '';
    return `You ${body}${atPlace} ${when}${who}, and I appreciate it.`;
  }

  // Fallback for a direct participant on a rumor-tagged event (still truthful).
  return `${fadedPrefix}I remember ${eventFactClause(e)} ${when}.`;
}

function formatHeardLine(
  e: LoopMemoryEvent,
  source: 'heard_friend' | 'heard_rumor',
  toldByName: string | undefined,
  ageDays: number,
  tone: MemoryTone,
  certainty: number,
): string {
  const clause = eventFactClause(e);
  const teller = cleanText(toldByName);
  const timeSuffix = ageDays > 14 ? ` ${timePhrase(ageDays, e.dayName)}` : '';

  if (source === 'heard_friend' && teller) {
    if (tone === 'downplayed') {
      return `${teller} told me ${clause}${timeSuffix}, though I hardly believe it.`;
    }
    if (tone === 'embellished') {
      return `${teller} told me ${clause}${timeSuffix} — everyone's talking about it, though I didn't see it myself.`;
    }
    if (certainty < 0.4) {
      return `I think ${teller} mentioned ${clause}${timeSuffix}, though I'm not sure of the details.`;
    }
    return `${teller} told me ${clause}${timeSuffix}, but I don't know if it's true.`;
  }

  if (teller) {
    if (tone === 'downplayed') {
      return `I heard from ${teller} that ${clause}${timeSuffix}, though it's probably just talk.`;
    }
    if (tone === 'embellished') {
      return `${teller} was going on about how ${clause}${timeSuffix}, though I wasn't there to see it.`;
    }
    return `I heard from ${teller} that ${clause}${timeSuffix}, though I only heard it secondhand.`;
  }

  if (tone === 'downplayed') {
    return `I heard some talk around town that ${clause}${timeSuffix}, though I take no stock in gossip.`;
  }
  if (tone === 'embellished') {
    return `People all over town are whispering that ${clause}${timeSuffix}, though I didn't see it myself.`;
  }
  return `Word around town is that ${clause}${timeSuffix}, though I don't know if it's true.`;
}

function baseSalienceAndHalfLife(
  e: LoopMemoryEvent,
  isVictim: boolean,
): { salience: number; halfLife: number } {
  switch (e.kind) {
    case 'crime': {
      const sev = clamp(e.severity ?? defaultCrimeSeverity(e.crimeKind), 0.1, 1.0);
      return {
        salience: clamp(0.68 + 0.28 * sev + (isVictim ? 0.12 : 0), 0.35, 1.0),
        halfLife: 35 + 85 * sev,
      };
    }
    case 'arrest': {
      const sev = clamp(e.severity ?? 0.75, 0.2, 1.0);
      return {
        salience: clamp(0.84 + 0.12 * sev, 0.7, 0.98),
        halfLife: 90,
      };
    }
    case 'trial': {
      const sev = clamp(e.severity ?? 0.8, 0.2, 1.0);
      return {
        salience: clamp(0.88 + 0.1 * sev, 0.75, 1.0),
        halfLife: 100,
      };
    }
    case 'stop': {
      const heavy = e.stopOutcome === 'arrested' || e.stopOutcome === 'searched';
      return {
        salience: heavy ? 0.74 : 0.62,
        halfLife: heavy ? 55 : 35,
      };
    }
    case 'work_shift':
      return { salience: 0.64, halfLife: 45 };
    case 'kindness':
      return { salience: 0.6, halfLife: 50 };
    case 'rumor': {
      const sev = clamp(e.severity ?? defaultCrimeSeverity(e.crimeKind), 0.15, 1.0);
      return {
        salience: clamp(0.55 + 0.28 * sev, 0.4, 0.9),
        halfLife: 28 + 32 * sev,
      };
    }
    case 'purchase':
      return {
        salience: e.hot ? 0.68 : 0.38,
        halfLife: e.hot ? 45 : 16,
      };
  }
}

interface KnowledgeHit {
  source: MemorySource;
  baseCertainty: number;
  sourceWeight: number;
  isVictim: boolean;
  toldByName?: string;
  heardDay: number;
}

function resolveKnowledge(
  personId: string,
  e: LoopMemoryEvent,
  friendSet: Set<string>,
): KnowledgeHit | null {
  const witness: LoopMemoryWitness | undefined = e.witnesses?.find((w) => w.personId === personId);
  const isDirectParticipant =
    e.employerId === personId ||
    e.clerkId === personId ||
    e.officerId === personId ||
    e.recipientId === personId ||
    e.victimId === personId ||
    Boolean(e.coworkerIds?.includes(personId)) ||
    Boolean(e.participants?.includes(personId));

  if (witness || isDirectParticipant) {
    const isVictim = Boolean(witness?.isVictim || e.victimId === personId);
    const rawConf = witness?.confidence ?? 0.94;
    // Direct/seen certainty stays strictly above heard_friend at matching age.
    const baseCertainty = clamp(0.78 + 0.21 * clamp(rawConf, 0, 1), 0.78, 0.99);
    const source: MemorySource =
      witness || e.kind === 'crime' || e.kind === 'stop' || e.kind === 'arrest' || e.kind === 'trial'
        ? 'seen'
        : 'direct';
    return {
      source,
      baseCertainty,
      sourceWeight: 1.0,
      isVictim,
      heardDay: e.day,
    };
  }

  const hearers: LoopMemoryHearer[] = (e.hearers ?? []).filter((h) => h.personId === personId);
  if (!hearers.length) return null;

  // Pick the strongest heard account if told more than once.
  let best: KnowledgeHit | null = null;
  for (let i = 0; i < hearers.length; i++) {
    const h = hearers[i]!;
    const hops = Math.max(1, h.hops ?? 1);
    const fromFriend =
      (h.fromFriend === true || (h.toldById !== undefined && friendSet.has(h.toldById))) &&
      hops <= 1;
    const source: MemorySource = fromFriend ? 'heard_friend' : 'heard_rumor';
    const rawConf = clamp(h.confidence ?? (fromFriend ? 0.72 : 0.45), 0.1, 1.0);
    const baseCertainty = fromFriend
      ? clamp(0.52 + 0.2 * rawConf, 0.52, 0.72)
      : clamp((0.24 + 0.22 * rawConf) * Math.pow(0.86, Math.max(0, hops - 1)), 0.14, 0.46);
    const candidate: KnowledgeHit = {
      source,
      baseCertainty,
      sourceWeight: fromFriend ? 0.78 : 0.56,
      isVictim: false,
      toldByName: cleanText(h.toldByName),
      heardDay: h.dayHeard ?? e.day,
    };
    if (!best || candidate.baseCertainty > best.baseCertainty) {
      best = candidate;
    }
  }
  return best;
}

/**
 * Turns a plain-JSON list of loop events into the most relevant first-person memories (at most 6)
 * for `personId` at `now`. Pure and deterministic; every returned line maps back to its `eventId`.
 */
export function memoriesFor(
  personId: string,
  events: readonly LoopMemoryEvent[],
  now: MemoryNow,
  options?: MemoryOptions,
): MemoryLine[] {
  if (!personId || !events || events.length === 0) return [];
  const nowDay = resolveNowDay(now);
  const friendSet = new Set<string>(options?.friendIds ?? []);
  const max = clamp(Math.floor(options?.max ?? MAX_MEMORIES), 1, MAX_MEMORIES);
  const minRelevance = options?.minRelevance ?? 0.1;

  const byEventId = new Map<string, MemoryLine>();

  for (let i = 0; i < events.length; i++) {
    const e = events[i]!;
    if (!e || !e.id) continue;
    const eventAge = nowDay - e.day;
    if (eventAge < 0) continue; // Ignore future events

    const hit = resolveKnowledge(personId, e, friendSet);
    if (!hit) continue;

    const heardAge = Math.max(0, nowDay - hit.heardDay);
    // Blending event age and time-since-heard so fresh gossip about an older event still feels current,
    // while both fade steadily as days pass.
    const effectiveAge =
      hit.source === 'seen' || hit.source === 'direct'
        ? eventAge
        : Math.round((heardAge * 0.7 + eventAge * 0.3) * 100) / 100;

    const { salience, halfLife } = baseSalienceAndHalfLife(e, hit.isVictim);
    const decay = Math.pow(0.5, effectiveAge / halfLife);
    const tone = resolveTone(e, options);

    const certainty = round3(clamp(hit.baseCertainty * (0.25 + 0.75 * decay), 0.05, 0.99));
    const toneBoost =
      tone === 'embellished' ? 1.08 : tone === 'downplayed' ? 0.92 : 1.0;
    const relevance = round3(
      clamp(salience * hit.sourceWeight * (0.12 + 0.88 * decay) * toneBoost, 0, 1.5),
    );

    if (decay < 0.08 && relevance < minRelevance) continue;
    if (relevance < minRelevance) continue;

    const heardOnly = hit.source === 'heard_friend' || hit.source === 'heard_rumor';
    const line =
      hit.source === 'heard_friend' || hit.source === 'heard_rumor'
        ? formatHeardLine(e, hit.source, hit.toldByName, eventAge, tone, certainty)
        : formatDirectLine(personId, e, eventAge, tone, certainty, options?.playerName);

    const entry: MemoryLine = {
      eventId: e.id,
      kind: e.kind,
      line,
      source: hit.source,
      heardOnly,
      ...(hit.toldByName ? { toldBy: hit.toldByName } : {}),
      certainty,
      relevance,
      ageDays: eventAge,
      tone,
      cues: buildCues(e, hit.toldByName),
    };

    const existing = byEventId.get(e.id);
    if (!existing || entry.relevance > existing.relevance) {
      byEventId.set(e.id, entry);
    }
  }

  const ranked = Array.from(byEventId.values()).sort(
    (a, b) =>
      b.relevance - a.relevance ||
      b.certainty - a.certainty ||
      a.ageDays - b.ageDays ||
      (a.eventId < b.eventId ? -1 : a.eventId > b.eventId ? 1 : 0),
  );

  return ranked.slice(0, max);
}

/** Plain-JSON wrapper for phone / QuickJS-ng bridge calls. */
export function memoriesForJson(
  personId: string,
  eventsJson: string,
  nowJson: string,
  optionsJson?: string,
): string {
  const events = JSON.parse(eventsJson) as LoopMemoryEvent[];
  const now = JSON.parse(nowJson) as MemoryNow;
  const options = optionsJson ? (JSON.parse(optionsJson) as MemoryOptions) : undefined;
  return JSON.stringify(memoriesFor(personId, events, now, options));
}
