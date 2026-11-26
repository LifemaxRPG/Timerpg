import { describe, expect, it } from 'vitest';
import { runTimeline, worldAt, isAlive } from '../sim';
import { narrate, type TalkCtx } from '../game/dialogue';
import type { LoopMemoryEvent } from '../loop/types';
import {
  MAX_MEMORIES,
  brief,
  freeQuestion,
  memoriesFor,
  memoriesForJson,
  validate,
  whyRejected,
  withMemories,
  withRecent,
} from './factsheet';

const w = worldAt(runTimeline({ seed: 'alpha', endYear: 1990 }), 1990);
const npc = Object.values(w.people).find((p) => isAlive(p) && w.year - p.birthYear >= 25)!;
const baseCtx: TalkCtx = {
  w,
  person: npc,
  job: "works at Fairchild's",
  townName: 'Testville',
  familiarity: 0,
  witnesses: 0,
  wanted: false,
  journal: [],
};

describe('G021: NPC first-person memories from loop events', () => {
  it('gives a witness the seen line, a friend who was told the hedged line, and a stranger nothing', () => {
    const events: LoopMemoryEvent[] = [
      {
        id: 'ev:theft:1',
        kind: 'crime',
        crimeKind: 'shoplifting',
        day: 10,
        dayName: 'Tuesday',
        placeId: 'place:fairchilds',
        placeName: "Fairchild's",
        itemLabel: 'watch',
        severity: 0.65,
        witnesses: [{ personId: 'p:clerk', confidence: 0.94, recognizedPlayer: true, isVictim: true }],
        hearers: [
          {
            personId: 'p:friend',
            toldById: 'p:lois',
            toldByName: 'Lois',
            fromFriend: true,
            hops: 1,
            confidence: 0.72,
            dayHeard: 11,
          },
          {
            personId: 'p:townie',
            hops: 2,
            confidence: 0.45,
            dayHeard: 11,
          },
        ],
      },
      {
        id: 'ev:arrest:1',
        kind: 'arrest',
        day: 11,
        dayName: 'Wednesday',
        placeName: 'Main Street',
        hearers: [
          {
            personId: 'p:friend',
            toldById: 'p:lois',
            toldByName: 'Lois',
            fromFriend: true,
            hops: 1,
            dayHeard: 11,
          },
        ],
      },
    ];

    const now = { day: 12, dayName: 'Thursday' };
    const witnessMems = memoriesFor('p:clerk', events, now);
    const friendMems = memoriesFor('p:friend', events, now);
    const townieMems = memoriesFor('p:townie', events, now);
    const strangerMems = memoriesFor('p:stranger', events, now);

    // Witness: direct first-person seen line
    expect(witnessMems).toHaveLength(1);
    expect(witnessMems[0]!.eventId).toBe('ev:theft:1');
    expect(witnessMems[0]!.source).toBe('seen');
    expect(witnessMems[0]!.heardOnly).toBe(false);
    expect(witnessMems[0]!.line).toBe("I saw you take a watch from Fairchild's on Tuesday.");

    // Friend who was told by Lois: hedged lines attributing Lois
    expect(friendMems).toHaveLength(2);
    const friendArrest = friendMems.find((m) => m.eventId === 'ev:arrest:1')!;
    expect(friendArrest.source).toBe('heard_friend');
    expect(friendArrest.heardOnly).toBe(true);
    expect(friendArrest.toldBy).toBe('Lois');
    expect(friendArrest.line).toBe("Lois told me you were picked up by the police at Main Street, but I don't know if it's true.");

    const friendTheft = friendMems.find((m) => m.eventId === 'ev:theft:1')!;
    expect(friendTheft.source).toBe('heard_friend');
    expect(friendTheft.heardOnly).toBe(true);
    expect(friendTheft.line).toContain('Lois told me');
    expect(friendTheft.line).toContain("Fairchild's");
    expect(friendTheft.line).not.toMatch(/\bI saw\b/i);

    // Second-hand town rumour: lower certainty than heard_friend, which is lower than seen
    expect(townieMems).toHaveLength(1);
    expect(townieMems[0]!.source).toBe('heard_rumor');
    expect(townieMems[0]!.heardOnly).toBe(true);
    expect(witnessMems[0]!.certainty).toBeGreaterThan(friendTheft.certainty);
    expect(friendTheft.certainty).toBeGreaterThan(townieMems[0]!.certainty);

    // Stranger: nothing
    expect(strangerMems).toEqual([]);
  });

  it('covers coworker shifts (with player name), stops, trials, purchases, and kindness', () => {
    const events: LoopMemoryEvent[] = [
      {
        id: 'ev:shift:1',
        kind: 'work_shift',
        day: 9,
        dayName: 'Monday',
        placeName: "Fairchild's",
        employerId: 'p:boss',
        coworkerIds: ['p:coworker'],
        playerName: 'Alex Mercer',
        hours: 6,
      },
      {
        id: 'ev:stop:1',
        kind: 'stop',
        day: 10,
        dayName: 'Tuesday',
        placeName: 'Elm Corner',
        officerId: 'p:officer',
        stopOutcome: 'warned',
      },
      {
        id: 'ev:trial:1',
        kind: 'trial',
        day: 11,
        dayName: 'Wednesday',
        charges: ['burglary'],
        verdict: 'guilty',
        participants: ['p:juror'],
      },
      {
        id: 'ev:buy:1',
        kind: 'purchase',
        day: 11,
        dayName: 'Wednesday',
        placeName: 'Bakery',
        itemLabel: 'loaf of bread',
        clerkId: 'p:baker',
      },
      {
        id: 'ev:kind:1',
        kind: 'kindness',
        day: 11,
        dayName: 'Wednesday',
        placeName: 'Bakery',
        recipientId: 'p:baker',
        kindnessDetail: 'helped carry crates',
        playerName: 'Alex Mercer',
      },
    ];

    const coworker = memoriesFor('p:coworker', events, { day: 12 });
    expect(coworker[0]!.line).toContain('worked a shift with me');
    expect(coworker[0]!.line).toContain('Alex');
    expect(coworker[0]!.eventId).toBe('ev:shift:1');

    const officer = memoriesFor('p:officer', events, { day: 12 });
    expect(officer[0]!.line).toContain('I stopped you at Elm Corner');
    expect(officer[0]!.line).toContain('warning');

    const juror = memoriesFor('p:juror', events, { day: 12 });
    expect(juror[0]!.line).toContain('in court');
    expect(juror[0]!.line).toContain('guilty');

    const baker = memoriesFor('p:baker', events, { day: 12 });
    expect(baker).toHaveLength(2);
    expect(baker.some((m) => m.eventId === 'ev:kind:1' && m.line.includes('helped carry crates'))).toBe(true);
    expect(baker.some((m) => m.eventId === 'ev:buy:1' && m.line.includes('loaf of bread'))).toBe(true);
  });

  it('fades memories with time, ranks by relevance, caps at 6, and maps every line back to its event id', () => {
    const theft: LoopMemoryEvent = {
      id: 'ev:fade:theft',
      kind: 'crime',
      crimeKind: 'burglary',
      day: 10,
      dayName: 'Tuesday',
      placeName: "Fairchild's",
      itemLabel: 'silver watch',
      severity: 0.78,
      witnesses: [{ personId: 'p:w', confidence: 0.92 }],
    };
    const bread: LoopMemoryEvent = {
      id: 'ev:fade:bread',
      kind: 'purchase',
      day: 10,
      dayName: 'Tuesday',
      placeName: 'Bakery',
      itemLabel: 'roll',
      clerkId: 'p:w',
    };

    const fresh = memoriesFor('p:w', [theft, bread], { day: 12 });
    const later = memoriesFor('p:w', [theft, bread], { day: 55 });
    const ancient = memoriesFor('p:w', [theft, bread], { day: 180 });

    const tFresh = fresh.find((m) => m.eventId === 'ev:fade:theft')!;
    const tLater = later.find((m) => m.eventId === 'ev:fade:theft')!;
    const tAncient = ancient.find((m) => m.eventId === 'ev:fade:theft')!;
    expect(tFresh.certainty).toBeGreaterThan(tLater.certainty);
    expect(tLater.certainty).toBeGreaterThan(tAncient.certainty);
    expect(tFresh.relevance).toBeGreaterThan(tLater.relevance);
    expect(tLater.line).toContain('a while back');
    expect(tAncient.line).toContain('some time ago');

    // Routine purchase fades out completely after 180 days while burglary still lingers
    expect(fresh.some((m) => m.eventId === 'ev:fade:bread')).toBe(true);
    expect(ancient.some((m) => m.eventId === 'ev:fade:bread')).toBe(false);

    // Feed 12 events -> capped at MAX_MEMORIES (6), ranked by relevance, every eventId grounded
    const many: LoopMemoryEvent[] = [];
    for (let i = 1; i <= 10; i++) {
      many.push({
        id: `ev:purchase:${i}`,
        kind: 'purchase',
        day: 40 + i,
        placeName: 'Bakery',
        itemLabel: `bun ${i}`,
        clerkId: 'p:clerk',
      });
    }
    many.push(
      {
        id: 'ev:major:arson',
        kind: 'crime',
        crimeKind: 'arson',
        day: 50,
        dayName: 'Friday',
        placeName: "Fairchild's",
        severity: 0.95,
        witnesses: [{ personId: 'p:clerk', confidence: 0.95 }],
      },
      {
        id: 'ev:future:ignored',
        kind: 'crime',
        crimeKind: 'robbery',
        day: 60,
        placeName: 'Bank',
        witnesses: [{ personId: 'p:clerk', confidence: 0.95 }],
      },
    );

    const ranked = memoriesFor('p:clerk', many, { day: 51 });
    expect(ranked.length).toBe(MAX_MEMORIES);
    expect(ranked.length).toBeLessThanOrEqual(6);
    expect(ranked[0]!.eventId).toBe('ev:major:arson');
    expect(ranked.some((m) => m.eventId === 'ev:future:ignored')).toBe(false);

    const inputIds = new Set(many.map((e) => e.id));
    const seenIds = new Set<string>();
    for (let i = 0; i < ranked.length; i++) {
      const m = ranked[i]!;
      expect(inputIds.has(m.eventId)).toBe(true);
      expect(seenIds.has(m.eventId)).toBe(false);
      seenIds.add(m.eventId);
      if (i > 0) {
        expect(ranked[i - 1]!.relevance).toBeGreaterThanOrEqual(m.relevance);
      }
    }
  });

  it('shades memories by traits: loyal friend plays it down, gossip plays it up while staying hedged on hearsay', () => {
    const ev: LoopMemoryEvent = {
      id: 'ev:arrest:2',
      kind: 'arrest',
      day: 20,
      dayName: 'Thursday',
      hearers: [
        {
          personId: 'p:npc',
          toldById: 'p:lois',
          toldByName: 'Lois',
          fromFriend: true,
          hops: 1,
          dayHeard: 20,
        },
      ],
    };

    const loyal = memoriesFor('p:npc', [ev], 21, {
      relationToPlayer: 'friend',
      traits: { loyalty: 0.9, charm: 0.5, diligence: 0.6 },
    })[0]!;
    const gossip = memoriesFor('p:npc', [ev], 21, {
      traits: { charm: 0.85, diligence: 0.25 },
    })[0]!;
    const neutral = memoriesFor('p:npc', [ev], 21)[0]!;

    expect(loyal.tone).toBe('downplayed');
    expect(loyal.line).toContain('hardly believe');
    expect(loyal.heardOnly).toBe(true);

    expect(gossip.tone).toBe('embellished');
    expect(gossip.line).toContain("everyone's talking about it");
    expect(gossip.line).toContain("didn't see it myself");
    expect(gossip.heardOnly).toBe(true);
    expect(gossip.relevance).toBeGreaterThan(loyal.relevance);

    expect(neutral.tone).toBe('neutral');
    expect(neutral.line).toContain("don't know if it's true");
  });

  it('injects memories into brief() / withRecent() and validate() rejects claiming sight on heard-only memories', () => {
    const events: LoopMemoryEvent[] = [
      {
        id: 'ev:watch:heard',
        kind: 'crime',
        crimeKind: 'shoplifting',
        day: 10,
        dayName: 'Tuesday',
        placeName: "Fairchild's",
        itemLabel: 'watch',
        witnesses: [{ personId: 'p:witness', confidence: 0.95 }],
        hearers: [
          {
            personId: 'p:hearer',
            toldById: 'p:lois',
            toldByName: 'Lois',
            fromFriend: true,
            hops: 1,
            dayHeard: 11,
          },
        ],
      },
      {
        id: 'ev:arrest:heard',
        kind: 'arrest',
        day: 11,
        dayName: 'Wednesday',
        hearers: [
          {
            personId: 'p:witness',
            toldById: 'p:lois',
            toldByName: 'Lois',
            fromFriend: true,
            hops: 1,
            dayHeard: 11,
          },
        ],
      },
    ];

    const hearerMems = memoriesFor('p:hearer', events, { day: 12 });
    const hearerBrief = brief(baseCtx, 'chat', narrate(baseCtx, 'chat'), [], [], hearerMems);
    expect(hearerBrief.system).toContain('Lois told me');
    expect(hearerBrief.system).toContain('The player is a stranger.'); // heard-only doesn't mean they met
    expect(hearerBrief.system).toContain('Only claim to have seen things you witnessed yourself');

    // Claiming to have seen a heard-only event is rejected by validate()
    expect(validate("I saw you take a watch from Fairchild's on Tuesday.", hearerBrief)).toBeNull();
    expect(whyRejected("I saw you take a watch from Fairchild's on Tuesday.", hearerBrief)).toBe(
      'claimed to have seen something only heard',
    );
    expect(whyRejected("I witnessed you take that watch with my own eyes.", hearerBrief)).toBe(
      'claimed to have seen something only heard',
    );
    // Tag question still claims sight -> rejected
    expect(whyRejected("I saw you take a watch from Fairchild's, didn't I?", hearerBrief)).toBe(
      'claimed to have seen something only heard',
    );

    // Truthful hedged attribution passes validate() (and Lois / Fairchild's are grounded in sheet)
    expect(
      validate("Lois told me you took a watch from Fairchild's, though I didn't see it myself.", hearerBrief),
    ).toBeTruthy();
    expect(validate('Lois mentioned you had trouble, but I cannot say if it is true.', hearerBrief)).toBeTruthy();

    // Mixed memories via withRecent(b, recent, memories): saw the watch theft, only heard about the arrest
    const witnessMems = memoriesFor('p:witness', events, { day: 12 });
    const mixedBase = brief(baseCtx, 'chat', narrate(baseCtx, 'chat'));
    const mixedBrief = withRecent(
      freeQuestion(mixedBase, 'What do you know about me?'),
      ['Hello there.'],
      witnessMems,
    );
    expect(mixedBrief.system).toContain("I saw you take a watch from Fairchild's on Tuesday.");
    expect(mixedBrief.system).toContain('Lois told me you were picked up by the police');
    expect(mixedBrief.system).toContain('The player is someone you have crossed paths with before.');
    expect(mixedBrief.system).toContain('You already said: "Hello there."');

    // Witness CAN say they saw the watch theft
    expect(validate("I saw you take a watch from Fairchild's on Tuesday.", mixedBrief)).toBeTruthy();
    // Witness CANNOT claim to have seen the arrest they only heard about from Lois
    expect(whyRejected('I saw the police pick you up.', mixedBrief)).toBe(
      'claimed to have seen something only heard',
    );
    // Witness CAN combine both truthfully
    expect(
      validate(
        "I saw you take a watch from Fairchild's, and Lois told me the police picked you up.",
        mixedBrief,
      ),
    ).toBeTruthy();

    // Direct withMemories() with plain string lines also enforces the hearsay rule
    const stringBrief = withMemories(mixedBase, [
      "Lois told me you were picked up by the police, but I don't know if it's true.",
    ]);
    expect(whyRejected('I saw you picked up by the police.', stringBrief)).toBe(
      'claimed to have seen something only heard',
    );
    expect(validate('Lois told me you were picked up by the police.', stringBrief)).toBeTruthy();
  });

  it('works as pure plain JSON via memoriesForJson', () => {
    const events: LoopMemoryEvent[] = [
      {
        id: 'ev:json:1',
        kind: 'crime',
        crimeKind: 'theft',
        day: 5,
        dayName: 'Tuesday',
        placeName: "Fairchild's",
        itemLabel: 'watch',
        witnesses: [{ personId: 'p:clerk', confidence: 0.9 }],
      },
    ];
    const jsonOut = memoriesForJson('p:clerk', JSON.stringify(events), JSON.stringify({ day: 6 }));
    const parsed = JSON.parse(jsonOut);
    expect(parsed).toEqual(memoriesFor('p:clerk', events, { day: 6 }));
    expect(parsed[0].eventId).toBe('ev:json:1');
  });
});
