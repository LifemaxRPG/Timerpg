import type { MemoryLine } from './memories';

// CONVERSATION TRACKING, TOPIC VARIETY & MOOD DERIVATION (G024).
// Pure, deterministic helpers that attach short multi-turn context, already-told topic markers,
// and a fact-grounded mood statement to a Brief so small local models follow the talk, vary
// topics instead of looping on one rumour, and never turn hostile without cause in the facts.

export type ConversationVoiceKind = 'gossip' | 'warm' | 'cheerful' | 'busy' | 'reserved' | 'shy' | 'grump';

export interface ConversationTurn {
  /** What the player asked or said on this turn. */
  player: string;
  /** What the NPC replied on this turn. */
  npc: string;
  /** Optional topic intent (e.g. "rumors", "work", "family", "chat", "origin"). */
  topic?: string;
  /** Optional explicit fact/rumour keys told during this turn (e.g. "rumor:Margaret Singh"). */
  toldFacts?: string[];
}

export interface ToldTopic {
  /** Stable identifier (e.g. "rumor:Margaret Singh", "topic:work", "origin"). */
  id: string;
  /** Short plain description of what was already told. */
  summary: string;
  /** Key subject tokens (names/keywords) belonging to this already-told item. */
  subjects: string[];
  /** Optional topic intent this item came from. */
  topic?: string;
}

export type MoodKind =
  | 'warm'
  | 'friendly'
  | 'polite'
  | 'tired'
  | 'guarded'
  | 'annoyed'
  | 'afraid'
  | 'hostile';

export type MoodStrength = 'mild' | 'moderate' | 'strong';

export interface MoodState {
  mood: MoodKind;
  strength: MoodStrength;
  /** Grounded reasons from the facts that produced this mood. */
  reasons: string[];
  /** True ONLY when the facts justify sharp hostility, threats, or calling the police. */
  allowsHostility: boolean;
  /** Plain one-line mood instruction for the fact sheet. */
  promptLine: string;
}

export interface MoodInput {
  familiarity: number;
  wanted?: boolean;
  voiceKind?: ConversationVoiceKind;
  /** Optional continuous attitude (-1 hostile .. +1 warm). */
  attitude?: number;
  /** Grounded G021 memories attached to this NPC/conversation. */
  memories?: readonly MemoryLine[];
  /** Local hour of day (0..23), optional. */
  hour?: number;
  /** What the player just said in the current turn. */
  playerSaid?: string;
  /** Prior turns in this conversation. */
  turns?: readonly ConversationTurn[];
}

/** Explicit hostile/abusive/threatening player speech that genuinely provokes an NPC. */
const HOSTILE_PLAYER =
  /\b(shut up|idiot|moron|stupid|bastard|asshole|bitch|fuck|screw you|go to hell|drop dead|get out of my way|hand over|empty your pockets|give me your money|rob you|kill you|hurt you|punch you|hit you|fight me|or else|i'll burn|threaten)\b/i;

/** Mildly pushy/impatient player speech (justifies slight annoyance, not police threats). */
const RUDE_PLAYER =
  /\b(none of your business|quit stalling|stop lying|you're lying|liar|waste of time|pathetic|boring|whatever|don't care)\b/i;

/** Warm/polite player phrasing. */
const POLITE_PLAYER =
  /\b(please|thank you|thanks|appreciate|lovely|nice to meet|good morning|good afternoon|good evening|how are you|hope you're well|kind of you)\b/i;

/** Explicit NPC hostility/threats that must NEVER appear without a grounded reason in the facts. */
export const HOSTILE_REPLY =
  /\b(shut up|call the police|call the cops|calling the police|calling the cops|have you arrested|get the hell out|go to hell|drop dead|beat it or i'll|get lost before i|leave me alone before i|get out of my face|i'll knock|i'll hit you|scram before i|you piece of|you idiot|you moron)\b/i;

/** Signals that the NPC is honestly closing an exhausted topic rather than retelling it. */
const CLOSING_TOPIC =
  /\b(nothing (?:else|more)|that's all i (?:know|heard|got)|all i know|already told you|don't know (?:any|anything) (?:else|more)|haven't heard (?:any|anything) (?:else|more)|no more (?:news|gossip|rumou?rs|details)|can't add (?:anything|much)|nothing new)\b/i;

const STOP_WORDS = new Set([
  'the', 'and', 'but', 'for', 'with', 'from', 'that', 'this', 'have', 'has', 'had', 'was', 'were',
  'are', 'you', 'your', 'they', 'them', 'their', 'she', 'her', 'him', 'his', 'our', 'not', 'just',
  'about', 'around', 'town', 'here', 'there', 'what', 'when', 'where', 'who', 'why', 'how', 'some',
  'any', 'all', 'more', 'very', 'really', 'quite', 'keep', 'quiet', 'well', 'know', 'heard', 'told',
  'said', 'says', 'think', 'been', 'being', 'will', 'would', 'could', 'should', 'can', 'did', 'does',
]);

function clip(s: string, max: number): string {
  const clean = s.replace(/\s+/g, ' ').trim();
  if (clean.length <= max) return clean;
  const cut = clean.slice(0, max - 1);
  const space = cut.lastIndexOf(' ');
  return (space > max * 0.55 ? cut.slice(0, space) : cut).replace(/[,;:\s]+$/, '') + '…';
}

function significantTokens(text: string): string[] {
  return Array.from(
    new Set(
      text
        .toLowerCase()
        .replace(/[^a-z0-9'\s]+/g, ' ')
        .split(/\s+/)
        .map((w) => w.replace(/^'+|'+$/g, '').replace(/'s$/, ''))
        .filter((w) => w.length >= 3 && !STOP_WORDS.has(w)),
    ),
  );
}

/** Extract capitalized proper-name spans (e.g. "Margaret Singh", "Arthur Calloway") from an NPC line. */
function extractProperNames(text: string): string[] {
  const skip = new Set([
    'The', 'This', 'That', 'There', 'Well', 'Yes', 'No', 'Oh', 'Ah', 'Hey', 'Hi', 'Hello',
    'Good', 'Nice', 'Sure', 'Not', 'Nothing', 'Nobody', 'Someone', 'Everyone', 'Maybe', 'Perhaps',
    'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday',
    'January', 'February', 'March', 'April', 'May', 'June', 'July', 'August',
    'September', 'October', 'November', 'December', 'Christmas', 'Easter',
    'Mr', 'Mrs', 'Ms', 'Miss', 'Dr', 'Sir', 'Madam', 'God', 'Lord',
  ]);
  const out: string[] = [];
  const matches = text.matchAll(/\b([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)\b/g);
  for (const m of matches) {
    const phrase = m[1]!;
    const first = phrase.split(' ')[0]!;
    if (skip.has(phrase) || (phrase.indexOf(' ') < 0 && skip.has(first))) continue;
    // Skip single sentence-initial capitalized word unless it's a 2-word full name.
    const before = text.slice(0, m.index).trimEnd();
    if ((!before || /[.!?"“‘]$/.test(before)) && !phrase.includes(' ')) continue;
    if (!out.includes(phrase)) out.push(phrase);
  }
  return out;
}

/** Short label for what the player asked on a turn. */
function summarizePlayerAsk(player: string, topic?: string): string {
  const p = player.replace(/\s+/g, ' ').trim();
  if (!p) return topic ? `asked about ${topic}` : 'spoke';
  const low = p.toLowerCase();
  if (/^(hi|hey|hello|yo|good morning|good afternoon|good evening)\b/.test(low) && p.length < 30) {
    return 'greeted you';
  }
  if (/\b(from here|born here|move here|moved here|lived here)\b/.test(low)) {
    return 'asked where you are from';
  }
  if (/\b(secret|rumou?r|gossip|heard anything|whisper|dirt)\b/.test(low)) {
    return 'asked for town gossip/secrets';
  }
  if (/\b(what else|anything else|more about|tell me more)\b/.test(low)) {
    return 'asked for more details';
  }
  if (/\b(work|job|living|shop|store)\b/.test(low)) {
    return 'asked about your work';
  }
  if (/\b(family|married|spouse|wife|husband|kids|children)\b/.test(low)) {
    return 'asked about your family';
  }
  return `asked "${clip(p, 46)}"`;
}

/**
 * Build a concise, bounded summary of the last few turns so a small local model stays on track
 * without blowing the prompt budget.
 */
export function summarizeConversation(
  turns: readonly ConversationTurn[],
  maxTurns = 4,
): string {
  const slice = turns
    .filter((t) => t.player.trim() || t.npc.trim())
    .slice(-maxTurns);
  if (!slice.length) return '';

  const parts = slice.map((t, i) => {
    const ask = summarizePlayerAsk(t.player, t.topic);
    const toldTag =
      t.toldFacts && t.toldFacts.length
        ? `told them: ${clip(t.toldFacts.join('; '), 60)}`
        : `replied "${clip(t.npc, 58)}"`;
    return `${i + 1}) Player ${ask}; you ${toldTag}.`;
  });

  return `Conversation so far: ${parts.join(' ')} Answer what the player just asked now; do not repeat earlier answers.`;
}

/**
 * Derive already-told topics/facts from prior turns plus any explicitly passed `told` items.
 */
export function collectToldTopics(
  turns: readonly ConversationTurn[],
  explicit: readonly ToldTopic[] = [],
): ToldTopic[] {
  const byId = new Map<string, ToldTopic>();
  for (const item of explicit) {
    byId.set(item.id, {
      ...item,
      subjects: item.subjects.length ? item.subjects : significantTokens(item.summary),
    });
  }

  for (const t of turns) {
    // 1. Explicit toldFacts on the turn (highest fidelity).
    if (t.toldFacts && t.toldFacts.length) {
      for (const raw of t.toldFacts) {
        const clean = raw.trim();
        if (!clean) continue;
        const id = clean.toLowerCase();
        if (!byId.has(id)) {
          const names = extractProperNames(clean);
          const toks = names.length
            ? Array.from(new Set(names.flatMap((n) => n.toLowerCase().split(/\s+/))))
            : significantTokens(clean).slice(0, 6);
          byId.set(id, {
            id,
            summary: clip(clean, 72),
            subjects: toks,
            topic: t.topic,
          });
        }
      }
      continue;
    }

    // 2. Infer told rumour/topic from the NPC's reply when no explicit toldFacts were attached.
    if (!t.npc.trim()) continue;
    const names = extractProperNames(t.npc);
    if ((t.topic === 'rumors' || t.topic === 'secret') && names.length) {
      const id = `${t.topic}:${names.join('+').toLowerCase()}`;
      if (!byId.has(id)) {
        byId.set(id, {
          id,
          summary: `${t.topic === 'rumors' ? 'rumour' : 'secret'} about ${names.join(' and ')}`,
          subjects: Array.from(new Set(names.flatMap((n) => n.toLowerCase().split(/\s+/)))),
          topic: t.topic,
        });
      }
    } else if (t.topic && ['origin', 'work', 'family', 'legend', 'news'].includes(t.topic)) {
      const id = `topic:${t.topic}`;
      if (!byId.has(id)) {
        const toks = names.length
          ? Array.from(new Set(names.flatMap((n) => n.toLowerCase().split(/\s+/))))
          : significantTokens(t.npc).slice(0, 5);
        byId.set(id, {
          id,
          summary: `${t.topic} (${clip(t.npc, 48)})`,
          subjects: toks,
          topic: t.topic,
        });
      }
    }
  }

  return Array.from(byId.values());
}

/**
 * Format the told-topic prompt line(s) and determine whether the current topic is exhausted
 * (allowing "I have nothing more to add" without triggering deflection rejection).
 */
export function formatToldGuidance(
  told: readonly ToldTopic[],
  currentTopic?: string,
  freshFacts: readonly string[] = [],
): { lines: string[]; exhausted: boolean } {
  if (!told.length) return { lines: [], exhausted: false };

  const list = told.slice(-4).map((t) => t.summary).join('; ');
  const lines = [`Already told in this talk: ${list}.`];

  const sameTopicAlreadyTold =
    !!currentTopic && told.some((t) => t.topic === currentTopic || t.id.startsWith(`${currentTopic}:`));

  if (sameTopicAlreadyTold) {
    if (freshFacts.length > 0) {
      lines.push(
        'Do not retell what you already told; share the new detail instead.',
      );
      return { lines, exhausted: false };
    }
    lines.push(
      'You already told all you know on this topic. Do not repeat it; say plainly you have nothing more on it, or answer any new part of their question.',
    );
    return { lines, exhausted: true };
  }

  lines.push('Do not bring up those already-told details again unless the player asks a new question about them.');
  return { lines, exhausted: false };
}

/**
 * Check whether [reply] is stuck retelling an already-told rumour/fact instead of adding something new
 * or honestly closing the topic.
 */
export function repeatsToldTopic(
  reply: string,
  told: readonly ToldTopic[],
  freshFactLines: readonly string[] = [],
): ToldTopic | null {
  if (!told.length) return null;
  // If the NPC is explicitly closing the topic ("nothing more", "that's all I know", "already told you"),
  // mentioning the name to close it ("I've nothing more to say about Margaret") is good behaviour!
  if (CLOSING_TOPIC.test(reply)) return null;

  const replyTokens = new Set(significantTokens(reply));
  const freshTokens = new Set(freshFactLines.flatMap(significantTokens));

  for (const item of told) {
    const subs = item.subjects.filter((s) => s.length >= 3);
    if (!subs.length) continue;

    // Count how many subject tokens from the already-told fact reappear in the reply.
    const matched = subs.filter((s) => replyTokens.has(s.toLowerCase()));
    const hitStrong = subs.length >= 2 ? matched.length >= 2 : matched.length >= 1 && (item.topic === 'rumors' || item.topic === 'secret');
    if (!hitStrong) continue;

    // If a fresh fact introduces new tokens and the reply actually mentions one of those new tokens,
    // the NPC is adding new information rather than looping.
    const addedFresh = Array.from(replyTokens).some(
      (tok) => freshTokens.has(tok) && !subs.includes(tok),
    );
    if (addedFresh) continue;

    // Only flag as stuck on an already-told rumour/secret/fact when the reply isn't clearly
    // answering a fresh unrelated question (e.g. player asked about a different subject and NPC
    // pivoted straight back to the same rumour).
    if (item.topic === 'rumors' || item.topic === 'secret' || item.id.startsWith('rumor:') || item.id.startsWith('secret:')) {
      return item;
    }
  }
  return null;
}

/**
 * Derive a plain, fact-grounded mood statement from familiarity/attitude, G021 memories,
 * time of day, voice manner, and what the player just said.
 * Enforces: NO hostility without an explicit reason in the facts.
 */
export function deriveMood(input: MoodInput): MoodState {
  const reasons: string[] = [];
  const memories = input.memories ?? [];
  const playerText = input.playerSaid ?? '';

  // 1. Check hard hostility / fear grounds from the facts.
  const wanted = !!input.wanted;
  const seenBadMemory = memories.find(
    (m) => !m.heardOnly && (m.kind === 'crime' || m.kind === 'arrest' || m.kind === 'trial'),
  );
  const heardBadMemory = memories.find(
    (m) => m.heardOnly && (m.kind === 'crime' || m.kind === 'arrest' || m.kind === 'rumor'),
  );
  const positiveMemory = memories.find(
    (m) => !m.heardOnly && (m.kind === 'kindness' || m.kind === 'purchase' || m.kind === 'work_shift'),
  );
  const seenStopMemory = memories.find((m) => !m.heardOnly && m.kind === 'stop');

  const playerHostile =
    HOSTILE_PLAYER.test(playerText) ||
    (input.turns ?? []).slice(-2).some((t) => HOSTILE_PLAYER.test(t.player));
  const playerRude = !playerHostile && RUDE_PLAYER.test(playerText);
  const playerPolite = !playerHostile && !playerRude && POLITE_PLAYER.test(playerText);

  const lateNight = input.hour !== undefined && (input.hour >= 22 || input.hour < 6);
  const attitude = input.attitude;

  let mood: MoodKind = 'polite';
  let strength: MoodStrength = 'mild';
  let allowsHostility = false;

  if (wanted) {
    mood = 'afraid';
    strength = 'strong';
    allowsHostility = true;
    reasons.push('the player is wanted for a crime');
  } else if (playerHostile) {
    mood = 'hostile';
    strength = 'strong';
    allowsHostility = true;
    reasons.push('the player threatened or insulted you');
  } else if (seenBadMemory) {
    mood = seenBadMemory.kind === 'crime' ? 'guarded' : 'annoyed';
    strength = 'strong';
    allowsHostility = true;
    reasons.push('you witnessed the player in trouble with the law');
  } else if (attitude !== undefined && attitude <= -0.7) {
    mood = 'hostile';
    strength = 'moderate';
    allowsHostility = true;
    reasons.push('you have a bitter history with the player');
  } else if (playerRude) {
    mood = 'annoyed';
    strength = 'moderate';
    allowsHostility = false; // sharp/terse is fine, still no "call the police" over mild rudeness
    reasons.push('the player spoke rudely to you');
  } else if (attitude !== undefined && attitude <= -0.35) {
    mood = 'guarded';
    strength = 'moderate';
    allowsHostility = false;
    reasons.push('you do not get on well with the player');
  } else if (seenStopMemory) {
    mood = 'guarded';
    strength = 'mild';
    allowsHostility = false;
    reasons.push('you saw the police stop the player');
  } else if (heardBadMemory) {
    mood = 'guarded';
    strength = 'mild';
    allowsHostility = false;
    reasons.push('you heard troubling talk about the player');
  } else if (lateNight) {
    mood = 'tired';
    strength = 'mild';
    allowsHostility = false;
    reasons.push('it is late at night');
  } else if (input.familiarity >= 3 || (attitude !== undefined && attitude >= 0.6)) {
    mood = 'warm';
    strength = positiveMemory ? 'strong' : 'moderate';
    allowsHostility = false;
    reasons.push(input.familiarity >= 3 ? 'the player is a friend' : 'you think well of the player');
    if (positiveMemory) reasons.push('you remember a good turn between you');
  } else if (input.familiarity >= 1 || positiveMemory || playerPolite || (attitude !== undefined && attitude >= 0.25)) {
    mood = 'friendly';
    strength = 'mild';
    allowsHostility = false;
    if (input.familiarity >= 1) reasons.push('you know the player a little');
    else if (positiveMemory) reasons.push('you remember dealing with the player');
    else if (playerPolite) reasons.push('the player spoke politely');
    else reasons.push('you feel well disposed');
  } else {
    mood = 'polite';
    strength = 'mild';
    allowsHostility = false;
    reasons.push('the player is a stranger');
  }

  // Note late-night fatigue alongside another non-fear mood if not already noted.
  if (lateNight && mood !== 'tired' && !wanted && !playerHostile) {
    reasons.push('it is late at night');
  }

  // Manner nuance without letting a grump invent unprovoked hostility.
  const mannerNote =
    input.voiceKind === 'grump' && !allowsHostility
      ? ' Be brief or gruff if that fits your manner, but stay civil.'
      : '';

  const boundary = allowsHostility
    ? 'You may be sharp, refuse to engage, or warn them off.'
    : 'Stay civil; do not threaten, insult, or talk about calling the police.';

  const reasonStr = reasons.join('; ');
  const promptLine = `Mood toward the player: ${mood} (show it ${strength}ly — ${reasonStr}). ${boundary}${mannerNote}`;

  return {
    mood,
    strength,
    reasons,
    allowsHostility,
    promptLine,
  };
}

/** True when [reply] contains unprovoked hostility/police threats unsupported by the facts. */
export function isUnprovokedHostility(
  reply: string,
  mood?: MoodState,
  sheet = '',
  playerSaid = '',
): boolean {
  if (!HOSTILE_REPLY.test(reply)) return false;
  if (mood) return !mood.allowsHostility;
  // Backward-compatible fallback when withMood() was not explicitly called:
  // permit hostility only if facts mention wanted/crime/afraid or the player was hostile.
  if (HOSTILE_PLAYER.test(playerSaid)) return false;
  if (/\b(wanted for a crime|afraid of them|arrested you|saw you (?:steal|take|rob|break|hit))\b/i.test(sheet)) {
    return false;
  }
  return true;
}
