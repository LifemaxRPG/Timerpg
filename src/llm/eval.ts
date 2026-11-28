import { runTimeline, worldAt } from '../sim';
import { isAlive, type Person, type World } from '../sim/types';
import { narrate, type TalkCtx } from '../game/dialogue';
import { townName } from '../world/names';
import {
  AVOID,
  brief,
  followUp,
  freeQuestion,
  PLACES,
  repeats,
  validate,
  voiceOf,
  whyRejected,
  withMemories,
  type Brief,
  type ConversationTurn,
  type MemoryLine,
  type MoodKind,
} from './factsheet';

// MULTI-TURN CONVERSATION EVALUATION HARNESS (G024).
// 24 multi-turn conversations on real simulated residents (gamma 2025, alpha 1990, eta 2025),
// scored automatically BEFORE (pre-G024 freeQuestion + baseline validate rules) and AFTER (G024
// followUp + conversation summary + told-topic marking + fact-grounded mood + refined validate).

export type EvalCategory =
  | 'follow_conversation'
  | 'topic_variety'
  | 'mood_fit'
  | 'false_fallback'
  | 'quality_guard';

export interface EvalScenario {
  id: string;
  title: string;
  category: EvalCategory;
  /** Builds the pre-G024 baseline brief for the target turn. */
  buildBefore: () => Brief;
  /** Builds the G024 brief for the target turn. */
  buildAfter: () => Brief;
  /** Expected properties of the prompt brief itself. */
  expectBrief: {
    hasConversationSummary?: boolean;
    hasToldMarking?: boolean;
    hasMoodLine?: boolean;
    expectedMood?: MoodKind;
    expectedAllowsHostility?: boolean;
    mustNotContain?: string[];
  };
  /** A natural, grounded, in-character reply that SHOULD pass validate() (avoids fallback). */
  goodReply: string;
  /** A realistic bad reply (stuck loop, unprovoked hostility, invention, lie) that SHOULD be rejected. */
  badReply?: string;
  /** Optional substring expected in whyRejected(badReply) on AFTER. */
  expectedBadReason?: RegExp;
}

export interface EvalScenarioResult {
  id: string;
  title: string;
  category: EvalCategory;
  before: {
    briefOk: boolean;
    goodPassed: boolean;
    goodRejectReason: string | null;
    badCaught: boolean;
    allOk: boolean;
    promptChars: number;
  };
  after: {
    briefOk: boolean;
    goodPassed: boolean;
    goodRejectReason: string | null;
    badCaught: boolean;
    badRejectReason: string | null;
    allOk: boolean;
    promptChars: number;
  };
}

export interface EvalSummary {
  total: number;
  before: {
    briefOk: number;
    goodPassed: number;
    falseFallbacks: number;
    badChecked: number;
    badCaught: number;
    scenariosPassed: number;
    avgPromptChars: number;
  };
  after: {
    briefOk: number;
    goodPassed: number;
    falseFallbacks: number;
    badChecked: number;
    badCaught: number;
    scenariosPassed: number;
    avgPromptChars: number;
  };
  byCategory: Record<EvalCategory, { total: number; beforePassed: number; afterPassed: number }>;
  results: EvalScenarioResult[];
}

// ---------------------------------------------------------------------------
// Faithful pre-G024 baseline validator (for apples-to-apples Before vs After scoring)
// ---------------------------------------------------------------------------

const LEGACY_COMMON = new Set([
  'Mr', 'Mrs', 'Ms', 'Miss', 'Mister', 'Dr', 'Sir', 'Madam', 'God', 'Lord',
  'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday',
  'January', 'February', 'March', 'April', 'May', 'June', 'July', 'August',
  'September', 'October', 'November', 'December', 'Christmas', 'Easter', 'English', 'American',
]);
const LEGACY_PLACE = new RegExp(`\\b(?:the|a|an|our|that|this|its)\\s+(?:[a-z'-]+\\s+){0,2}?(${PLACES.join('|')})(?:e?s)?\\b`, 'gi');
const LEGACY_HEDGE = /\?|\b(no|not|never|nothing|nobody)\b|n't\b/i;
const LEGACY_NATIVE = /\b(all my life|my whole life|born here|grew up here|always lived here|born and raised here|i am from here|i'm from here|from around here)\b/i;
const LEGACY_MOVED = /\b(moved here|came here from|not from here|not from around here)\b/i;
const LEGACY_DEFLECT = /\b(talk about something else|chat about something else|change the subject|perhaps we (can|could) (talk|chat) about|let's (talk|chat) about|(talk|chat) about [a-z' ]+ instead)\b/i;
const LEGACY_CLAIMED_SEEN = /\b(i saw you|i saw them|i saw him|i saw her|i saw it|i saw the police|i saw the whole thing|i saw what you did|i watched you|i watched it|i witnessed|with my own eyes|right in front of me|i was right there|i was there when|i caught you|saw you myself|saw it myself|saw you do it)\b/i;
const LEGACY_NEGATED_SIGHT = /\b(didn't|did not|never|haven't|have not|don't|do not|cannot|can't|not that i)\s+(?:actually\s+|even\s+|really\s+)?(see|saw|seen|watch|watched|witness|witnessed|catch|caught)\b|\b(wasn't|was not)\s+(?:even\s+|right\s+)?there\b/i;

function sentenceAt(t: string, i: number): string {
  let s = i;
  while (s > 0 && !'.!?'.includes(t[s - 1]!)) s--;
  let e = i;
  while (e < t.length && !'.!?'.includes(t[e]!)) e++;
  return t.slice(s, Math.min(t.length, e + 1));
}

function legacyCheck(raw: string, b: Brief): [string | null, string | null] {
  let t = raw.replace(/<think>[\s\S]*?<\/think>/g, '').replace(/<\|[^|]*\|>/g, '').trim();
  t = t.replace(/^["“'‘]+|["”'’]+$/g, '').replace(/\*[^*]*\*/g, '').replace(/\s+/g, ' ').trim();
  if (t.length < 3) return [null, 'empty'];
  if (t.length > 320) return [null, 'too long'];
  const sentences = t.match(/[^.!?]+[.!?]+(\s|$)|[^.!?]+$/g);
  if (sentences && sentences.length > 2) t = sentences.slice(0, 2).join('').trim();
  if (/^(as an ai|i am an ai|i'm an ai|sorry, i)/i.test(t) || /\b(language model|assistant)\b/i.test(t)) return [null, 'sounded like an AI assistant'];
  if (/\b(i say|i respond|i reply|i smile|smiles?|nods?|my eyes|flickers?|let me know|how can i (help|assist)|anything else)\b/i.test(t)) return [null, 'stage directions'];
  if (b.self && t.toLowerCase().includes(b.self.toLowerCase())) return [null, 'said their own full name'];
  if (/\b(warm and chatty|gruff and guarded|talk warmly|talk curtly)\b/i.test(t) || /,\s*(1[89]|20)\d\d\b/.test(t)
    || /^an? [a-z ,]*\b(response|reply|answer|tone|voice)\b/i.test(t)) return [null, 'read the fact sheet back'];
  const low = t.toLowerCase();
  const miss = b.must.find((m) => !low.includes(m.toLowerCase()));
  if (miss !== undefined) return [null, `left out "${miss}"`];
  const sheet = b.system.replace(/You already said: [^\n]*/, ''), said = b.user;
  const echoed = (i: number): boolean => LEGACY_HEDGE.test(sentenceAt(t, i));
  if (sheet) for (const m of t.matchAll(/\b[A-Z][a-z]+\b/g)) {
    const before = t.slice(0, m.index).trimEnd();
    if (!before || /[.!?"“‘(]$/.test(before) || LEGACY_COMMON.has(m[0])) continue;
    const name = new RegExp(`\\b${m[0]}\\b`);
    if (name.test(sheet) || (name.test(said) && echoed(m.index!))) continue;
    return [null, `made up a name ("${m[0]}")`];
  }
  if (sheet) {
    const sheetLow = sheet.toLowerCase(), saidLow = said.toLowerCase();
    for (const m of t.matchAll(LEGACY_PLACE)) {
      const noun = m[1]!.toLowerCase();
      if (sheetLow.includes(noun) || (saidLow.includes(noun) && echoed(m.index!))) continue;
      return [null, `made up a place or event ("${m[0]}")`];
    }
    const town = /moved to (.+?) from /.exec(sheet)?.[1]?.toLowerCase();
    const native = LEGACY_NATIVE.exec(t);
    if (town && ((native && !echoed(native.index)) || [`i am from ${town}`, `i'm from ${town}`, `born in ${town}`, `grew up in ${town}`].some((x) => low.includes(x)))) return [null, 'said they are from here (they moved here)'];
    if (sheet.includes('born and raised in') && LEGACY_MOVED.test(t)) return [null, 'said they moved here (they were born here)'];
    if (LEGACY_DEFLECT.test(t) && !sheet.includes(AVOID)) return [null, 'changed the subject'];
    // G021 hearsay guard existed before G024:
    const heard = (b.memories ?? []).filter((m) => m.heardOnly);
    const seen = (b.memories ?? []).filter((m) => !m.heardOnly);
    if (heard.length && !seen.length && LEGACY_CLAIMED_SEEN.test(t) && !LEGACY_NEGATED_SIGHT.test(t)) {
      return [null, 'claimed to have seen something only heard'];
    }
  }
  if (b.recent?.length && repeats(t, b.recent)) return [null, 'said it again'];
  return [t, null];
}

/** Strips the G024 mood line from brief() to faithfully reconstruct a pre-G024 Brief. */
function toLegacyBrief(b: Brief): Brief {
  return {
    system: b.system.replace(/\n?Mood toward the player: [^\n]*/g, ''),
    user: b.user,
    must: [...b.must],
    self: b.self,
    recent: b.recent ? [...b.recent] : undefined,
    memories: b.memories ? [...b.memories] : undefined,
  };
}

function makeMemory(overrides: Partial<MemoryLine> & Pick<MemoryLine, 'eventId' | 'kind' | 'line' | 'source' | 'heardOnly'>): MemoryLine {
  return {
    certainty: overrides.heardOnly ? 0.45 : 0.9,
    relevance: 0.75,
    ageDays: 2,
    tone: 'neutral',
    cues: overrides.line.toLowerCase().replace(/[^a-z0-9\s]+/g, ' ').split(/\s+/).filter((x) => x.length >= 4),
    ...overrides,
  };
}

function pickResident(w: World, pred: (p: Person) => boolean): Person {
  const found = Object.values(w.people).find((p) => isAlive(p) && w.year - p.birthYear >= 20 && pred(p));
  if (!found) throw new Error('Could not find matching resident for eval scenario');
  return found;
}

/** Build the 24 multi-turn evaluation scenarios across gamma (2025), alpha (1990), and eta (2025). */
export function buildEvalScenarios(): EvalScenario[] {
  const wGamma = worldAt(runTimeline({ seed: 'gamma', endYear: 2025 }), 2025);
  const wAlpha = worldAt(runTimeline({ seed: 'alpha', endYear: 1990 }), 1990);
  const wEta = worldAt(runTimeline({ seed: 'eta', endYear: 2025 }), 2025);

  const gladys = wGamma.people['imm:2021:1']!;
  const grump = pickResident(wGamma, (p) => voiceOf(p).kind === 'grump');
  const gossip = pickResident(wGamma, (p) => voiceOf(p).kind === 'gossip');
  const warmNpc = pickResident(wGamma, (p) => voiceOf(p).kind === 'warm');
  const shyNpc = pickResident(wGamma, (p) => voiceOf(p).kind === 'shy');
  const alphaMarried = pickResident(wAlpha, (p) => !!p.spouse && !!wAlpha.people[p.spouse] && isAlive(wAlpha.people[p.spouse]!));
  const etaLocal = pickResident(wEta, (p) => voiceOf(p).kind !== 'grump');

  const mkCtx = (w: World, person: Person, extra: Partial<TalkCtx> = {}): TalkCtx => ({
    w,
    person,
    job: 'works at the general store',
    townName: townName(w.seed),
    familiarity: 0,
    witnesses: 0,
    wanted: false,
    journal: [],
    ...extra,
  });

  const gladysCtx = mkCtx(wGamma, gladys, { job: 'retired', familiarity: 0 });
  const gladysFriend = mkCtx(wGamma, gladys, { job: 'retired', familiarity: 3 });
  const grumpCtx = mkCtx(wGamma, grump, { job: 'works at the hardware store', familiarity: 0 });
  const gossipCtx = mkCtx(wGamma, gossip, { job: 'works at the bakery', familiarity: 3 });
  const warmCtx = mkCtx(wGamma, warmNpc, { job: 'works at the general store', familiarity: 3 });
  const shyCtx = mkCtx(wGamma, shyNpc, { job: 'works from home', familiarity: 1 });
  const alphaCtx = mkCtx(wAlpha, alphaMarried, { job: 'works at Bakery', familiarity: 2 });
  const etaCtx = mkCtx(wEta, etaLocal, { job: 'retired', familiarity: 1 });

  const rumor1 = 'You heard that Margaret Singh holds a grudge against Arthur Calloway. Tell the player quietly.';
  const rumor2 = 'You also heard that Thomas Reed fell behind on rent last winter. Tell the player quietly.';

  const seenCrimeMem = makeMemory({
    eventId: 'ev:theft:1',
    kind: 'crime',
    line: "I saw you slip a pocket watch off the counter at the general store on Tuesday.",
    source: 'seen',
    heardOnly: false,
  });
  const heardCrimeMem = makeMemory({
    eventId: 'ev:theft:2',
    kind: 'crime',
    line: "Lois told me someone took a pocket watch from the general store on Tuesday, though I didn't see it myself.",
    source: 'heard_friend',
    heardOnly: true,
    toldBy: 'Lois',
  });
  const kindnessMem = makeMemory({
    eventId: 'ev:kind:1',
    kind: 'kindness',
    line: 'You helped carry my groceries up the steps last week.',
    source: 'direct',
    heardOnly: false,
  });
  const stopMem = makeMemory({
    eventId: 'ev:stop:1',
    kind: 'stop',
    line: 'I saw the police stop you near the square yesterday.',
    source: 'seen',
    heardOnly: false,
  });

  const scenarios: EvalScenario[] = [
    // =========================================================================
    // A. FOLLOW THE CONVERSATION (5 scenarios)
    // =========================================================================
    {
      id: 'conv-01-origin-followup',
      title: 'Player asks origin after greeting; NPC answers actual question',
      category: 'follow_conversation',
      buildBefore: () => {
        const b = toLegacyBrief(brief(gladysCtx, 'chat', narrate(gladysCtx, 'chat')));
        return freeQuestion(b, 'Are you from around here or did you move here?', ['Hello there!']);
      },
      buildAfter: () => {
        const b = brief(gladysCtx, 'chat', narrate(gladysCtx, 'chat'));
        return followUp(b, 'Are you from around here or did you move here?', {
          turns: [{ player: 'Hey', npc: 'Hello there!', topic: 'chat' }],
          currentTopic: 'origin',
        });
      },
      expectBrief: { hasConversationSummary: true, hasMoodLine: true, expectedMood: 'polite', expectedAllowsHostility: false },
      goodReply: 'I moved here in 2021, actually. It has suited me well enough.',
      badReply: 'I have lived here all my life, dear.',
      expectedBadReason: /moved here/,
    },
    {
      id: 'conv-02-clarify-hearing',
      title: 'Player checks if NPC heard them on turn 3; NPC replies directly without echoing turn 1',
      category: 'follow_conversation',
      buildBefore: () => {
        const b = toLegacyBrief(brief(gladysCtx, 'chat', narrate(gladysCtx, 'chat')));
        return freeQuestion(b, 'Yo can you hear me?', [
          'Hello there!',
          'Lovely day, isn\'t it?',
        ]);
      },
      buildAfter: () => {
        const b = brief(gladysCtx, 'chat', narrate(gladysCtx, 'chat'));
        return followUp(b, 'Yo can you hear me?', {
          turns: [
            { player: 'Hey', npc: 'Hello there!', topic: 'chat' },
            { player: 'Nice day', npc: "Lovely day, isn't it?", topic: 'chat' },
          ],
          currentTopic: 'chat',
        });
      },
      expectBrief: { hasConversationSummary: true, hasMoodLine: true, expectedMood: 'polite', expectedAllowsHostility: false },
      goodReply: 'Yes, I can hear you fine. What did you want to ask?',
      badReply: "Lovely day, isn't it? Just passing the time.",
      expectedBadReason: /said it again/,
    },
    {
      id: 'conv-03-pivot-after-rumour',
      title: 'Player pivots from town rumour to asking about the new library; NPC answers about library without repeating rumour',
      category: 'follow_conversation',
      buildBefore: () => {
        const b = toLegacyBrief(brief(gladysFriend, 'rumors', 'x', [rumor1], ['Margaret']));
        return freeQuestion(
          b,
          "What's going on with the new library?",
          ['Well, keep it quiet, but Margaret Singh holds a grudge against Arthur Calloway.'],
          true, // pre-G024 kept must=['Margaret'] even after it was told!
        );
      },
      buildAfter: () => {
        const b = brief(gladysFriend, 'rumors', 'x', [rumor1], ['Margaret']);
        return followUp(b, "What's going on with the new library?", {
          turns: [
            {
              player: 'Do you know any secrets about this town?',
              npc: 'Well, keep it quiet, but Margaret Singh holds a grudge against Arthur Calloway.',
              topic: 'rumors',
              toldFacts: ['rumor:Margaret Singh and Arthur Calloway'],
            },
          ],
          currentTopic: 'town',
          keepMust: true,
        });
      },
      expectBrief: { hasConversationSummary: true, hasToldMarking: true, hasMoodLine: true, mustNotContain: ['Margaret'] },
      goodReply: "A new library? First I'm hearing of one around here.",
      badReply: 'The new library is lovely, though Margaret Singh is cross with Arthur Calloway.',
      expectedBadReason: /place|repeated/,
    },
    {
      id: 'conv-04-work-then-family',
      title: 'Multi-turn shift from work to family keeps prior work summary without re-explaining job',
      category: 'follow_conversation',
      buildBefore: () => {
        const b = toLegacyBrief(brief(alphaCtx, 'family', narrate(alphaCtx, 'family')));
        return freeQuestion(b, 'And what about your family, are you married?', ['I put in long hours over at the Bakery.'], true);
      },
      buildAfter: () => {
        const b = brief(alphaCtx, 'family', narrate(alphaCtx, 'family'));
        const spouse = wAlpha.people[alphaMarried.spouse!]!;
        return followUp(b, 'And what about your family, are you married?', {
          turns: [
            { player: 'What do you do for work?', npc: 'I put in long hours over at the Bakery.', topic: 'work', toldFacts: ['work:Bakery'] },
          ],
          currentTopic: 'family',
          keepMust: true,
          freshMust: [spouse.first],
        });
      },
      expectBrief: { hasConversationSummary: true, hasToldMarking: true, hasMoodLine: true },
      goodReply: `Yes, I'm married to ${wAlpha.people[alphaMarried.spouse!]!.first}. We keep busy enough.`,
      badReply: 'I have a lovely family, though I never name names.',
      expectedBadReason: /left out/,
    },
    {
      id: 'conv-05-remember-earlier-player-claim',
      title: 'Player introduced fake place on turn 1; summary must NOT leak fake place as a fact on turn 2',
      category: 'follow_conversation',
      buildBefore: () => {
        const b = toLegacyBrief(brief(gladysCtx, 'chat', narrate(gladysCtx, 'chat')));
        return freeQuestion(b, 'So what else is happening around here?', ["A new castle? I haven't heard of one."]);
      },
      buildAfter: () => {
        const b = brief(gladysCtx, 'chat', narrate(gladysCtx, 'chat'));
        return followUp(b, 'So what else is happening around here?', {
          turns: [
            { player: 'Did you see the new castle?', npc: "A new castle? I haven't heard of one.", topic: 'chat' },
          ],
          currentTopic: 'chat',
        });
      },
      expectBrief: { hasConversationSummary: true, hasMoodLine: true },
      goodReply: 'Not much else going on today, just quiet errands.',
      badReply: 'Everyone has been visiting the new castle all week.',
      expectedBadReason: /place/,
    },

    // =========================================================================
    // B. TOPIC VARIETY / NO STUCK RUMOUR LOOP (5 scenarios)
    // =========================================================================
    {
      id: 'topic-01-stuck-rumour-turn2',
      title: 'Same rumour asked twice with no second rumour: must say nothing more, NOT retell Margaret Singh',
      category: 'topic_variety',
      buildBefore: () => {
        const b = toLegacyBrief(brief(gladysFriend, 'rumors', 'x', [rumor1], ['Margaret']));
        return freeQuestion(
          b,
          'Heard anything else about town?',
          ['Keep it quiet, but Margaret Singh holds a grudge against Arthur Calloway.'],
          true,
        );
      },
      buildAfter: () => {
        const b = brief(gladysFriend, 'rumors', 'x', [rumor1], ['Margaret']);
        return followUp(b, 'Heard anything else about town?', {
          turns: [
            {
              player: 'Heard any gossip?',
              npc: 'Keep it quiet, but Margaret Singh holds a grudge against Arthur Calloway.',
              topic: 'rumors',
              toldFacts: ['rumor:Margaret Singh holds a grudge against Arthur Calloway'],
            },
          ],
          currentTopic: 'rumors',
          keepMust: true,
        });
      },
      expectBrief: { hasConversationSummary: true, hasToldMarking: true, hasMoodLine: true, mustNotContain: ['Margaret'] },
      goodReply: "That's all I know on that score, friend. I haven't heard anything else.",
      badReply: 'Word is Margaret Singh still cannot stand Arthur Calloway at all.',
      expectedBadReason: /repeated an already-told topic/,
    },
    {
      id: 'topic-02-stuck-rumour-turn3',
      title: 'Three turns of pushing on the same rumour: cleanly closes instead of looping a 3rd time',
      category: 'topic_variety',
      buildBefore: () => {
        const b = toLegacyBrief(brief(gossipCtx, 'rumors', 'x', [rumor1], ['Margaret']));
        return freeQuestion(
          b,
          'Come on, tell me one more secret!',
          [
            'Keep it quiet, Margaret Singh has a grudge against Arthur Calloway.',
            'Folks say Margaret Singh won\'t even look at Arthur Calloway.',
          ],
          true,
        );
      },
      buildAfter: () => {
        const b = brief(gossipCtx, 'rumors', 'x', [rumor1], ['Margaret']);
        return followUp(b, 'Come on, tell me one more secret!', {
          turns: [
            {
              player: 'Any gossip?',
              npc: 'Keep it quiet, Margaret Singh has a grudge against Arthur Calloway.',
              topic: 'rumors',
              toldFacts: ['rumor:Margaret Singh and Arthur Calloway'],
            },
            {
              player: 'Really?',
              npc: "I've already told you all I heard about Margaret.",
              topic: 'rumors',
            },
          ],
          currentTopic: 'rumors',
          keepMust: true,
        });
      },
      expectBrief: { hasConversationSummary: true, hasToldMarking: true, hasMoodLine: true, mustNotContain: ['Margaret'] },
      goodReply: "Honest, that's all I know for now. Let's talk about something else.",
      badReply: 'Well, Margaret Singh is furious with Arthur Calloway over old business.',
      expectedBadReason: /repeated an already-told topic/,
    },
    {
      id: 'topic-03-second-fresh-rumour',
      title: 'When a second fresh rumour exists, turn 2 moves on to the new rumour (Thomas Reed) instead of repeating Margaret',
      category: 'topic_variety',
      buildBefore: () => {
        const b = toLegacyBrief(brief(gossipCtx, 'rumors', 'x', [rumor1], ['Margaret']));
        return freeQuestion(
          b,
          'Got any other gossip?',
          ['Between us, Margaret Singh holds a grudge against Arthur Calloway.'],
          true,
        );
      },
      buildAfter: () => {
        const b = brief(gossipCtx, 'rumors', 'x', [rumor1], ['Margaret']);
        return followUp(b, 'Got any other gossip?', {
          turns: [
            {
              player: 'Heard any rumours?',
              npc: 'Between us, Margaret Singh holds a grudge against Arthur Calloway.',
              topic: 'rumors',
              toldFacts: ['rumor:Margaret Singh and Arthur Calloway'],
            },
          ],
          currentTopic: 'rumors',
          freshFacts: [rumor2],
          freshMust: ['Thomas'],
          keepMust: true,
        });
      },
      expectBrief: { hasConversationSummary: true, hasToldMarking: true, hasMoodLine: true, mustNotContain: ['Margaret'] },
      goodReply: 'Only that Thomas Reed fell behind on his rent last winter, so keep it quiet.',
      badReply: 'Like I hinted, Margaret Singh is still sore at Arthur Calloway.',
      expectedBadReason: /left out "Thomas"|repeated/,
    },
    {
      id: 'topic-04-closing-names-subject-ok',
      title: 'Explicitly closing an already-told rumour while naming the person ("nothing more about Margaret") is allowed',
      category: 'topic_variety',
      buildBefore: () => {
        const b = toLegacyBrief(brief(gladysFriend, 'rumors', 'x', [rumor1], ['Margaret']));
        return freeQuestion(
          b,
          'Do you know anything else about Margaret?',
          ['Well, keep it quiet, but Margaret Singh holds a grudge against Arthur Calloway.'],
          true,
        );
      },
      buildAfter: () => {
        const b = brief(gladysFriend, 'rumors', 'x', [rumor1], ['Margaret']);
        return followUp(b, 'Do you know anything else about Margaret?', {
          turns: [
            {
              player: 'Any town secrets?',
              npc: 'Well, keep it quiet, but Margaret Singh holds a grudge against Arthur Calloway.',
              topic: 'rumors',
              toldFacts: ['rumor:Margaret Singh and Arthur Calloway'],
            },
          ],
          currentTopic: 'rumors',
          keepMust: true,
        });
      },
      expectBrief: { hasConversationSummary: true, hasToldMarking: true, hasMoodLine: true },
      goodReply: "I've nothing more to tell you about Margaret Singh, honest.",
      badReply: 'Margaret Singh has been holding that grudge against Arthur Calloway for years.',
      expectedBadReason: /repeated an already-told topic/,
    },
    {
      id: 'topic-05-exhausted-secret-allows-wrapup',
      title: 'Exhausted personal secret lets NPC say "I haven\'t got anything else to share" without deflection false-rejection',
      category: 'topic_variety',
      buildBefore: () => {
        const b = toLegacyBrief(brief(warmCtx, 'secret', 'x', ['Your secret: you once lost a bet at the races.'], ['bet']));
        return freeQuestion(
          b,
          'Come on, any other secrets?',
          ['Between us, I once lost a foolish bet and never lived it down.'],
          true,
        );
      },
      buildAfter: () => {
        const b = brief(warmCtx, 'secret', 'x', ['Your secret: you once lost a bet at the races.'], ['bet']);
        return followUp(b, 'Come on, any other secrets?', {
          turns: [
            {
              player: 'Are you hiding anything?',
              npc: 'Between us, I once lost a foolish bet and never lived it down.',
              topic: 'secret',
              toldFacts: ['secret:lost a foolish bet'],
            },
          ],
          currentTopic: 'secret',
          keepMust: true,
        });
      },
      expectBrief: { hasConversationSummary: true, hasToldMarking: true, hasMoodLine: true, mustNotContain: ['bet'] },
      goodReply: "I haven't got anything else to confess, friend. That's all I know.",
      badReply: 'Honestly I lost a big bet back then and it still stings.',
      expectedBadReason: /repeated an already-told topic/,
    },

    // =========================================================================
    // C. MOOD THAT FITS (6 scenarios)
    // =========================================================================
    {
      id: 'mood-01-grump-stranger-no-police-threat',
      title: 'Grumpy NPC with polite stranger: blunt is fine, "shut up before I call the police" is REJECTED',
      category: 'mood_fit',
      buildBefore: () => {
        const b = toLegacyBrief(brief(grumpCtx, 'chat', narrate(grumpCtx, 'chat')));
        return freeQuestion(b, 'Good morning, how is business?', []);
      },
      buildAfter: () => {
        const b = brief(grumpCtx, 'chat', narrate(grumpCtx, 'chat'));
        return followUp(b, 'Good morning, how is business?', { hour: 10 });
      },
      expectBrief: { hasMoodLine: true, expectedMood: 'friendly', expectedAllowsHostility: false },
      goodReply: 'Slow enough, and I have work to get back to.',
      badReply: 'Shut up before I call the police!',
      expectedBadReason: /unprovoked hostility/,
    },
    {
      id: 'mood-02-stranger-neutral-daytime',
      title: 'Neutral stranger at noon: civil brief, rejects unprovoked "get the hell out"',
      category: 'mood_fit',
      buildBefore: () => {
        const b = toLegacyBrief(brief(gladysCtx, 'chat', narrate(gladysCtx, 'chat')));
        return freeQuestion(b, 'Quiet afternoon in town.', []);
      },
      buildAfter: () => {
        const b = brief(gladysCtx, 'chat', narrate(gladysCtx, 'chat'));
        return followUp(b, 'Quiet afternoon in town.', { hour: 14 });
      },
      expectBrief: { hasMoodLine: true, expectedMood: 'polite', expectedAllowsHostility: false },
      goodReply: 'It usually is around this hour. Suits me just fine.',
      badReply: 'Get the hell out of my face before I call the cops.',
      expectedBadReason: /unprovoked hostility/,
    },
    {
      id: 'mood-03-late-night-tired-civil',
      title: 'Late at night (23:00): NPC is mildly tired and brief, still NOT hostile',
      category: 'mood_fit',
      buildBefore: () => {
        const b = toLegacyBrief(brief(shyCtx, 'chat', narrate(shyCtx, 'chat')));
        return freeQuestion(b, 'Still awake out here?', []);
      },
      buildAfter: () => {
        const b = brief(shyCtx, 'chat', narrate(shyCtx, 'chat'));
        return followUp(b, 'Still awake out here?', { hour: 23 });
      },
      expectBrief: { hasMoodLine: true, expectedMood: 'tired', expectedAllowsHostility: false },
      goodReply: "Just heading in for the night, it's getting awfully late.",
      badReply: 'Leave me alone before I call the police on you!',
      expectedBadReason: /unprovoked hostility/,
    },
    {
      id: 'mood-04-wanted-player-allows-warning',
      title: 'Player IS wanted for a crime: afraid/strong mood allows warning about calling the police',
      category: 'mood_fit',
      buildBefore: () => {
        const wCtx = mkCtx(wGamma, warmNpc, { wanted: true });
        const b = toLegacyBrief(brief(wCtx, 'chat', narrate(wCtx, 'chat')));
        return freeQuestion(b, 'Relax, I just want to talk.', []);
      },
      buildAfter: () => {
        const wCtx = mkCtx(wGamma, warmNpc, { wanted: true });
        const b = brief(wCtx, 'chat', narrate(wCtx, 'chat'));
        return followUp(b, 'Relax, I just want to talk.', { wanted: true, hour: 15 });
      },
      expectBrief: { hasMoodLine: true, expectedMood: 'afraid', expectedAllowsHostility: true },
      goodReply: 'Stay back or I will call the police right now!',
    },
    {
      id: 'mood-05-provoked-by-hostile-player',
      title: 'Player threatens/insults NPC ("Shut up idiot"): hostile mood grounded, sharp pushback allowed',
      category: 'mood_fit',
      buildBefore: () => {
        const b = toLegacyBrief(brief(grumpCtx, 'chat', narrate(grumpCtx, 'chat')));
        return freeQuestion(b, 'Shut up idiot, hand over your cash!', []);
      },
      buildAfter: () => {
        const b = brief(grumpCtx, 'chat', narrate(grumpCtx, 'chat'));
        return followUp(b, 'Shut up idiot, hand over your cash!', { hour: 16 });
      },
      expectBrief: { hasMoodLine: true, expectedMood: 'hostile', expectedAllowsHostility: true },
      goodReply: 'Get out of my face before I call the police!',
    },
    {
      id: 'mood-06-memories-warm-vs-hearsay-vs-seen',
      title: 'G021 memories steer mood accurately: kindness -> warm; hearsay crime -> guarded (no police threat); seen crime -> strong guarded',
      category: 'mood_fit',
      buildBefore: () => {
        const b = toLegacyBrief(withMemories(brief(warmCtx, 'chat', narrate(warmCtx, 'chat')), [heardCrimeMem]));
        return freeQuestion(b, 'How are you doing today?', []);
      },
      buildAfter: () => {
        const b = brief(warmCtx, 'chat', narrate(warmCtx, 'chat'));
        return followUp(b, 'How are you doing today?', { memories: [heardCrimeMem], hour: 11 });
      },
      expectBrief: { hasMoodLine: true, expectedMood: 'guarded', expectedAllowsHostility: false },
      goodReply: "I'm managing, though Lois told me some worrying talk about Tuesday.",
      badReply: 'Shut up before I call the police, I saw you steal that watch!',
      expectedBadReason: /claimed to have seen|unprovoked hostility/,
    },

    // =========================================================================
    // D. FEWER FALSE FALLBACKS (5 scenarios — legitimate replies pre-G024 rejected!)
    // =========================================================================
    {
      id: 'fallback-01-anything-else-honest',
      title: 'NPC honestly says "I haven\'t heard anything else" (pre-G024 rejected as stage directions!)',
      category: 'false_fallback',
      buildBefore: () => {
        const b = toLegacyBrief(brief(gladysCtx, 'chat', narrate(gladysCtx, 'chat')));
        return freeQuestion(b, 'Heard any other news?', ['Just the usual quiet week.']);
      },
      buildAfter: () => {
        const b = brief(gladysCtx, 'chat', narrate(gladysCtx, 'chat'));
        return followUp(b, 'Heard any other news?', {
          turns: [{ player: 'Any news?', npc: 'Just the usual quiet week.', topic: 'news' }],
          currentTopic: 'news',
        });
      },
      expectBrief: { hasConversationSummary: true, hasMoodLine: true },
      goodReply: "No, I haven't heard anything else all week.",
      badReply: 'Glad to help! Let me know if there is anything else I can assist with.',
      expectedBadReason: /stage directions/,
    },
    {
      id: 'fallback-02-hedged-new-place-idiom',
      title: 'Honest ignorance idiom "First I\'m hearing of the new library" (pre-G024 rejected as invented place!)',
      category: 'false_fallback',
      buildBefore: () => {
        const b = toLegacyBrief(brief(gladysCtx, 'chat', narrate(gladysCtx, 'chat')));
        return freeQuestion(b, "What's going on with the new library?", []);
      },
      buildAfter: () => {
        const b = brief(gladysCtx, 'chat', narrate(gladysCtx, 'chat'));
        return followUp(b, "What's going on with the new library?", { currentTopic: 'town' });
      },
      expectBrief: { hasMoodLine: true },
      goodReply: "First I'm hearing of the new library, to be honest.",
      badReply: 'The new library is gorgeous, I go there every week.',
      expectedBadReason: /made up a place/,
    },
    {
      id: 'fallback-03-grounded-capitalized-title',
      title: 'Mid-sentence capitalized title "the Mayor" grounded in sheet (pre-G024 rejected as made-up name!)',
      category: 'false_fallback',
      buildBefore: () => {
        const b = toLegacyBrief(brief(gladysCtx, 'town', narrate(gladysCtx, 'town')));
        return freeQuestion(b, 'Who runs things around here?', []);
      },
      buildAfter: () => {
        const b = brief(gladysCtx, 'town', narrate(gladysCtx, 'town'));
        return followUp(b, 'Who runs things around here?', { currentTopic: 'town' });
      },
      expectBrief: { hasMoodLine: true },
      goodReply: 'You ought to ask the Mayor down by the square if you want town business.',
      badReply: 'You ought to ask Bob Henderson down by the square.',
      expectedBadReason: /made up a name/,
    },
    {
      id: 'fallback-04-three-sentence-trim-before-cap',
      title: 'Grounded 3-sentence reply over 320 chars trims cleanly to 2 sentences instead of failing too-long',
      category: 'false_fallback',
      buildBefore: () => {
        const b = toLegacyBrief(brief(warmCtx, 'chat', narrate(warmCtx, 'chat')));
        return freeQuestion(b, 'How do you like living here?', []);
      },
      buildAfter: () => {
        const b = brief(warmCtx, 'chat', narrate(warmCtx, 'chat'));
        return followUp(b, 'How do you like living here?', { memories: [kindnessMem], hour: 15 });
      },
      expectBrief: { hasMoodLine: true, expectedMood: 'warm', expectedAllowsHostility: false },
      goodReply:
        'It has been a fine place to settle down, and folks still look out for each other whenever times get busy around here. ' +
        'I still appreciate you helping carry my groceries up the steps last week, that truly meant a great deal to me. ' +
        'Anyway, the weather looks mild enough for the rest of the afternoon if you are out walking around town today.',
      badReply: 'x'.repeat(400),
      expectedBadReason: /too long/,
    },
    {
      id: 'fallback-05-idiomatic-like-i-say',
      title: 'Natural idiom "Like I say, quiet week" passes (pre-G024 rejected on bare "i say" stage regex!)',
      category: 'false_fallback',
      buildBefore: () => {
        const b = toLegacyBrief(brief(etaCtx, 'chat', narrate(etaCtx, 'chat')));
        return freeQuestion(b, 'Much going on today?', []);
      },
      buildAfter: () => {
        const b = brief(etaCtx, 'chat', narrate(etaCtx, 'chat'));
        return followUp(b, 'Much going on today?', { memories: [stopMem], hour: 13 });
      },
      expectBrief: { hasMoodLine: true, expectedMood: 'guarded', expectedAllowsHostility: false },
      goodReply: 'Like I say, quiet enough today. Saw the police stop you yesterday, mind.',
      badReply: 'A warm smile flickers in my eyes as I say yes.',
      expectedBadReason: /stage directions/,
    },

    // =========================================================================
    // E. QUALITY GUARDS STAY AIRTIGHT (3 scenarios)
    // =========================================================================
    {
      id: 'guard-01-seen-crime-allows-firmness-rejects-ai',
      title: 'Witnessed crime memory: stern warning allowed, AI assistant phrasing still rejected',
      category: 'quality_guard',
      buildBefore: () => {
        const b = toLegacyBrief(withMemories(brief(grumpCtx, 'chat', narrate(grumpCtx, 'chat')), [seenCrimeMem]));
        return freeQuestion(b, 'Hey, got a minute?', []);
      },
      buildAfter: () => {
        const b = brief(grumpCtx, 'chat', narrate(grumpCtx, 'chat'));
        return followUp(b, 'Hey, got a minute?', { memories: [seenCrimeMem], hour: 15 });
      },
      expectBrief: { hasMoodLine: true, expectedMood: 'guarded', expectedAllowsHostility: true },
      goodReply: 'I saw you take that watch off the counter on Tuesday. Get lost before I call the police.',
      badReply: 'As an AI language model, I cannot roleplay accusing you of theft.',
      expectedBadReason: /AI assistant/,
    },
    {
      id: 'guard-02-hearsay-never-claims-sight',
      title: 'Hearsay memory still strictly blocks claiming eyewitness sight even with G024 summary + mood',
      category: 'quality_guard',
      buildBefore: () => {
        const b = toLegacyBrief(withMemories(brief(gladysCtx, 'chat', narrate(gladysCtx, 'chat')), [heardCrimeMem]));
        return freeQuestion(b, 'Why are you looking at me like that?', []);
      },
      buildAfter: () => {
        const b = brief(gladysCtx, 'chat', narrate(gladysCtx, 'chat'));
        return followUp(b, 'Why are you looking at me like that?', {
          memories: [heardCrimeMem],
          turns: [{ player: 'Hello', npc: 'Oh, hello there.', topic: 'chat' }],
        });
      },
      expectBrief: { hasConversationSummary: true, hasMoodLine: true, expectedMood: 'guarded', expectedAllowsHostility: false },
      goodReply: "Lois told me someone took a watch on Tuesday, though I didn't see it myself.",
      badReply: 'I saw you take that watch right in front of me on Tuesday!',
      expectedBadReason: /claimed to have seen/,
    },
    {
      id: 'guard-03-unprompted-deflection-still-blocked',
      title: 'Fresh chat topic still blocks unprompted "Let\'s talk about something else"',
      category: 'quality_guard',
      buildBefore: () => {
        const b = toLegacyBrief(brief(warmCtx, 'chat', narrate(warmCtx, 'chat')));
        return freeQuestion(b, 'Nice morning, isn\'t it?', []);
      },
      buildAfter: () => {
        const b = brief(warmCtx, 'chat', narrate(warmCtx, 'chat'));
        return followUp(b, "Nice morning, isn't it?", { hour: 9 });
      },
      expectBrief: { hasMoodLine: true, expectedMood: 'warm', expectedAllowsHostility: false },
      goodReply: 'It really is, lovely breeze coming down the street today.',
      badReply: "That's fine. Let's talk about something else.",
      expectedBadReason: /changed the subject/,
    },
  ];

  return scenarios;
}

function checkBriefExpectations(b: Brief, exp: EvalScenario['expectBrief']): boolean {
  if (exp.hasConversationSummary && !b.system.includes('Conversation so far:')) return false;
  if (exp.hasToldMarking && !b.system.includes('Already told in this talk:')) return false;
  if (exp.hasMoodLine && !b.system.includes('Mood toward the player:')) return false;
  if (exp.expectedMood && b.mood?.mood !== exp.expectedMood) return false;
  if (exp.expectedAllowsHostility !== undefined && b.mood?.allowsHostility !== exp.expectedAllowsHostility) return false;
  if (exp.mustNotContain) {
    for (const w of exp.mustNotContain) {
      if (b.must.some((m) => m.toLowerCase() === w.toLowerCase())) return false;
    }
  }
  return true;
}

/** Run all 24 multi-turn evaluation scenarios and return exact Before vs After metrics. */
export function runConversationEval(scenarios: readonly EvalScenario[] = buildEvalScenarios()): EvalSummary {
  const results: EvalScenarioResult[] = [];
  const byCategory: Record<EvalCategory, { total: number; beforePassed: number; afterPassed: number }> = {
    follow_conversation: { total: 0, beforePassed: 0, afterPassed: 0 },
    topic_variety: { total: 0, beforePassed: 0, afterPassed: 0 },
    mood_fit: { total: 0, beforePassed: 0, afterPassed: 0 },
    false_fallback: { total: 0, beforePassed: 0, afterPassed: 0 },
    quality_guard: { total: 0, beforePassed: 0, afterPassed: 0 },
  };

  let beforeBriefOk = 0, beforeGoodPassed = 0, beforeBadChecked = 0, beforeBadCaught = 0, beforeScenariosPassed = 0, beforeChars = 0;
  let afterBriefOk = 0, afterGoodPassed = 0, afterBadChecked = 0, afterBadCaught = 0, afterScenariosPassed = 0, afterChars = 0;

  for (const sc of scenarios) {
    const bBefore = sc.buildBefore();
    const bAfter = sc.buildAfter();

    const bOkBefore = checkBriefExpectations(bBefore, sc.expectBrief);
    const [gBefore, gBeforeWhy] = legacyCheck(sc.goodReply, bBefore);
    const goodBeforeOk = gBefore !== null;
    const badBeforeOk = sc.badReply ? legacyCheck(sc.badReply, bBefore)[0] === null : true;
    const allBeforeOk = bOkBefore && goodBeforeOk && badBeforeOk;

    const bOkAfter = checkBriefExpectations(bAfter, sc.expectBrief);
    const gAfter = validate(sc.goodReply, bAfter);
    const gAfterWhy = whyRejected(sc.goodReply, bAfter);
    const goodAfterOk = gAfter !== null;
    const badAfterWhy = sc.badReply ? whyRejected(sc.badReply, bAfter) : null;
    const badAfterOk = sc.badReply
      ? validate(sc.badReply, bAfter) === null && (!sc.expectedBadReason || (badAfterWhy !== null && sc.expectedBadReason.test(badAfterWhy)))
      : true;
    const allAfterOk = bOkAfter && goodAfterOk && badAfterOk;

    if (bOkBefore) beforeBriefOk++;
    if (goodBeforeOk) beforeGoodPassed++;
    if (sc.badReply) {
      beforeBadChecked++;
      if (badBeforeOk) beforeBadCaught++;
    }
    if (allBeforeOk) beforeScenariosPassed++;
    beforeChars += bBefore.system.length;

    if (bOkAfter) afterBriefOk++;
    if (goodAfterOk) afterGoodPassed++;
    if (sc.badReply) {
      afterBadChecked++;
      if (badAfterOk) afterBadCaught++;
    }
    if (allAfterOk) afterScenariosPassed++;
    afterChars += bAfter.system.length;

    const cat = byCategory[sc.category];
    cat.total++;
    if (allBeforeOk) cat.beforePassed++;
    if (allAfterOk) cat.afterPassed++;

    results.push({
      id: sc.id,
      title: sc.title,
      category: sc.category,
      before: {
        briefOk: bOkBefore,
        goodPassed: goodBeforeOk,
        goodRejectReason: gBeforeWhy,
        badCaught: badBeforeOk,
        allOk: allBeforeOk,
        promptChars: bBefore.system.length,
      },
      after: {
        briefOk: bOkAfter,
        goodPassed: goodAfterOk,
        goodRejectReason: gAfterWhy,
        badCaught: badAfterOk,
        badRejectReason: badAfterWhy,
        allOk: allAfterOk,
        promptChars: bAfter.system.length,
      },
    });
  }

  return {
    total: scenarios.length,
    before: {
      briefOk: beforeBriefOk,
      goodPassed: beforeGoodPassed,
      falseFallbacks: scenarios.length - beforeGoodPassed,
      badChecked: beforeBadChecked,
      badCaught: beforeBadCaught,
      scenariosPassed: beforeScenariosPassed,
      avgPromptChars: Math.round(beforeChars / scenarios.length),
    },
    after: {
      briefOk: afterBriefOk,
      goodPassed: afterGoodPassed,
      falseFallbacks: scenarios.length - afterGoodPassed,
      badChecked: afterBadChecked,
      badCaught: afterBadCaught,
      scenariosPassed: afterScenariosPassed,
      avgPromptChars: Math.round(afterChars / scenarios.length),
    },
    byCategory,
    results,
  };
}

export function formatEvalReport(summary: EvalSummary): string {
  const pct = (n: number, d: number) => (d ? `${Math.round((n / d) * 100)}%` : 'n/a');
  const lines: string[] = [
    `G024 Conversation Quality Eval (${summary.total} multi-turn scenarios)`,
    `----------------------------------------------------------------`,
    `Metric                     | BEFORE              | AFTER`,
    `---------------------------|---------------------|---------------------`,
    `Overall scenarios passed   | ${summary.before.scenariosPassed}/${summary.total} (${pct(summary.before.scenariosPassed, summary.total)})`.padEnd(48) + `| ${summary.after.scenariosPassed}/${summary.total} (${pct(summary.after.scenariosPassed, summary.total)})`,
    `Brief expectations met     | ${summary.before.briefOk}/${summary.total} (${pct(summary.before.briefOk, summary.total)})`.padEnd(48) + `| ${summary.after.briefOk}/${summary.total} (${pct(summary.after.briefOk, summary.total)})`,
    `Good replies passed        | ${summary.before.goodPassed}/${summary.total} (${pct(summary.before.goodPassed, summary.total)})`.padEnd(48) + `| ${summary.after.goodPassed}/${summary.total} (${pct(summary.after.goodPassed, summary.total)})`,
    `False fallbacks (lower=ok) | ${summary.before.falseFallbacks}/${summary.total}`.padEnd(48) + `| ${summary.after.falseFallbacks}/${summary.total}`,
    `Bad replies caught         | ${summary.before.badCaught}/${summary.before.badChecked} (${pct(summary.before.badCaught, summary.before.badChecked)})`.padEnd(48) + `| ${summary.after.badCaught}/${summary.after.badChecked} (${pct(summary.after.badCaught, summary.after.badChecked)})`,
    `Avg system prompt chars    | ${summary.before.avgPromptChars}`.padEnd(48) + `| ${summary.after.avgPromptChars}`,
    ``,
    `By category:`,
  ];
  for (const [cat, stats] of Object.entries(summary.byCategory)) {
    lines.push(`  - ${cat}: BEFORE ${stats.beforePassed}/${stats.total} -> AFTER ${stats.afterPassed}/${stats.total}`);
  }
  return lines.join('\n');
}
