import { fullName, isAlive, type Id } from '../sim';
import { presentAt } from './cache';
import { branch } from './interventions';
import type {
  CarriedItem,
  CarriedItemStatus,
  NpcMemoryView,
  PlayerMemory,
  PlayerNpcInteraction,
  SelfEraseEffect,
  SelfEraseReport,
  Timeline,
} from './types';

/**
 * Adds a carried item to the unanchored player's inventory without mutating `timeline` (Rule 1).
 * Carried items keep their history even if their maker or shop is erased in a later branch.
 */
export function addCarriedItem(timeline: Timeline, item: CarriedItem): Timeline {
  const copy: CarriedItem = { ...item };
  const idx = timeline.player.inventory.findIndex((i) => i.id === item.id);
  const inventory =
    idx >= 0
      ? timeline.player.inventory.map((i, k) => (k === idx ? copy : i))
      : [...timeline.player.inventory, copy];
  return {
    ...timeline,
    player: {
      ...timeline.player,
      inventory,
    },
  };
}

/**
 * Records a memory the unanchored player carries across timeline jumps (Rule 1).
 * Never erased when the world re-forms.
 */
export function addPlayerMemory(timeline: Timeline, memory: PlayerMemory): Timeline {
  const copy: PlayerMemory = { ...memory };
  const idx = timeline.player.memories.findIndex((m) => m.id === memory.id);
  const memories =
    idx >= 0
      ? timeline.player.memories.map((m, k) => (k === idx ? copy : m))
      : [...timeline.player.memories, copy];
  return {
    ...timeline,
    player: {
      ...timeline.player,
      memories,
    },
  };
}

/**
 * Records an interaction with an anchored NPC inside THIS timeline branch (Rule 2).
 * Only this branch's re-formed NPC remembers it; discarded branches are forgotten.
 */
export function recordPlayerInteraction(
  timeline: Timeline,
  interaction: PlayerNpcInteraction,
): Timeline {
  return {
    ...timeline,
    player: {
      ...timeline.player,
      interactions: [...timeline.player.interactions, { ...interaction }],
    },
  };
}

/**
 * Inspects every item in the player's inventory against the replayed world at `year` (Rule 1).
 * Even if an item's maker or business never existed in this timeline, `survives` is always `true`
 * and its original provenance stays intact.
 */
export function inspectCarriedItems(timeline: Timeline, year: number): CarriedItemStatus[] {
  const snap = presentAt(timeline, year);
  const w = snap.world;

  return timeline.player.inventory.map((item) => {
    const maker = item.makerId ? w.people[item.makerId] : undefined;
    const makerExists = item.makerId
      ? Boolean(maker && maker.birthYear <= item.acquiredYear && (maker.deathYear === undefined || maker.deathYear >= item.acquiredYear))
      : true;
    const biz = item.businessId ? w.businesses[item.businessId] : undefined;
    const businessExists = item.businessId
      ? Boolean(biz && (biz.closedYear === undefined || biz.closedYear >= item.acquiredYear))
      : true;
    const anachronisticProvenance = !makerExists || !businessExists;

    let note: string;
    if (!makerExists && !businessExists) {
      note = `Survives in inventory (unanchored), though neither ${item.makerName ?? item.makerId} nor ${item.businessName ?? item.businessId} existed by ${item.acquiredYear} in this timeline.`;
    } else if (!makerExists) {
      note = `Survives in inventory (unanchored), though its maker ${item.makerName ?? item.makerId} never existed in this timeline.`;
    } else if (!businessExists) {
      note = `Survives in inventory (unanchored), though ${item.businessName ?? item.businessId} was gone before ${item.acquiredYear} in this timeline.`;
    } else {
      note = `Consistent with the current timeline (${item.provenanceNote}).`;
    }

    return {
      item: { ...item },
      survives: true,
      makerExists,
      businessExists,
      anachronisticProvenance,
      note,
    };
  });
}

/**
 * Answers what an anchored NPC remembers about the player in `year` (Rule 2).
 * Everyone else re-forms with each timeline change: their memories of the player come strictly
 * from the current timeline's interactions (and pre-erasure family ties vanish on `selfErase`).
 */
export function npcMemoryOfPlayer(
  timeline: Timeline,
  npcId: Id,
  year: number,
): NpcMemoryView {
  const snap = presentAt(timeline, year);
  const person = snap.world.people[npcId];

  if (!person || person.birthYear > year) {
    return {
      npcId,
      year,
      existsInTimeline: false,
      aliveInYear: false,
      remembersPlayer: false,
      knowsAsFamily: false,
      interactions: [],
      summary: `Person ${npcId} does not exist in ${year} in this timeline.`,
    };
  }

  const name = fullName(person);
  const alive = isAlive(person);

  // Only interactions in THIS timeline at or before `year` (and after the NPC was born) count.
  const interactions = timeline.player.interactions
    .filter((ix) => ix.npcId === npcId && ix.year <= year && ix.year >= person.birthYear)
    .map((ix) => ({ ...ix }))
    .sort((a, b) => a.year - b.year || a.id.localeCompare(b.id));

  // If the player erased their own anchored self, family who used to know them no longer do.
  const knowsAsFamily =
    !timeline.player.selfErased &&
    timeline.player.records.knownFamilyIds.includes(npcId) &&
    year >= timeline.player.birthYear;

  const remembersPlayer = knowsAsFamily || interactions.length > 0;

  let summary: string;
  if (!alive) {
    summary = `${name} is deceased by ${year} (died ${person.deathYear}).`;
  } else if (knowsAsFamily && interactions.length > 0) {
    summary = `${name} knows the player as family and recalls ${interactions.length} interaction(s) in this timeline.`;
  } else if (knowsAsFamily) {
    summary = `${name} knows the player as family in this timeline.`;
  } else if (interactions.length > 0) {
    summary = `${name} recalls ${interactions.length} interaction(s) with the player in this timeline.`;
  } else if (timeline.player.selfErased) {
    summary = `${name} has no memory of the player (the player has no anchored past in this timeline).`;
  } else {
    summary = `${name} does not remember the player in this timeline.`;
  }

  return {
    npcId,
    npcName: name,
    year,
    existsInTimeline: true,
    aliveInYear: alive,
    remembersPlayer,
    knowsAsFamily,
    interactions,
    summary,
  };
}

/**
 * Rule 3: The player removes their own anchored self in `year`.
 * Returns a new `Timeline` (never mutating the input) and a structured `SelfEraseReport`
 * listing erased identity facts, what the player loses, and what they gain.
 */
export function selfErase(
  timeline: Timeline,
  year: number,
  placeId?: string,
): { timeline: Timeline; report: SelfEraseReport } {
  const targetPlace = placeId ?? timeline.player.homePlaceId ?? timeline.placeId;
  const p = timeline.player;

  if (p.selfErased) {
    const report: SelfEraseReport = {
      year,
      placeId: targetPlace,
      playerId: p.playerId,
      ...(p.anchoredPersonId !== undefined ? { anchoredPersonId: p.anchoredPersonId } : {}),
      alreadyErased: true,
      erasedRecords: [],
      losses: [],
      gains: [],
      summary: [`${p.playerId} already erased their anchored self in ${p.selfErasedYear ?? year}.`],
    };
    return { timeline, report };
  }

  const erasedRecords: string[] = [];
  const losses: SelfEraseEffect[] = [];
  const gains: SelfEraseEffect[] = [];

  if (p.records.birthRecord) {
    erasedRecords.push('birth_record');
    losses.push({
      code: 'no_birth_record',
      label: 'No birth record',
      detail: 'No municipal registry shows you were ever born.',
    });
  }
  if (p.records.legalIdentity) {
    erasedRecords.push('legal_identity');
    losses.push({
      code: 'no_legal_identity',
      label: 'No legal identity',
      detail: 'Cannot pass official background checks, open legitimate leases, or claim state benefits.',
    });
  }
  if (p.records.bankAccount) {
    erasedRecords.push('bank_account');
    losses.push({
      code: 'no_bank_account',
      label: 'Bank account vanished',
      detail: 'Anchored bank accounts, credit history, and balances under your name cease to exist.',
    });
  }
  if (p.records.passportEligible) {
    erasedRecords.push('passport');
    losses.push({
      code: 'no_legal_passport',
      label: 'No legal passport path',
      detail: 'Cannot obtain a legal passport; crossing oceans requires forged papers or illegal passage.',
    });
  }
  if (p.records.jobReferences.length > 0) {
    erasedRecords.push('job_references');
    losses.push({
      code: 'no_references',
      label: 'Lost job & personal references',
      detail: `${p.records.jobReferences.length} reference(s) no longer remember employing or vouching for you.`,
    });
  }
  if (p.records.knownFamilyIds.length > 0 || p.anchoredPersonId !== undefined) {
    erasedRecords.push('family_ties');
    losses.push({
      code: 'no_family_recognition',
      label: 'Family no longer knows you',
      detail:
        p.records.knownFamilyIds.length > 0
          ? `${p.records.knownFamilyIds.length} family member(s) have no memory of you.`
          : 'Nobody in town recognizes you from a shared past.',
    });
  }

  // Gains from self-erasure (Rule 3 & IDEAS.md)
  if (p.records.fingerprintsOnFile || (timeline.identityFiles[p.playerId]?.some((r) => r.hasPrints) ?? false)) {
    erasedRecords.push('fingerprints_on_file');
  }
  gains.push({
    code: 'no_prints_on_file',
    label: 'No fingerprints on file',
    detail: 'Police fingerprint registries and AFIS databases contain no matching prints for you.',
  });

  if (p.records.dnaOnFile || (timeline.identityFiles[p.playerId]?.some((r) => r.hasDna) ?? false)) {
    erasedRecords.push('dna_on_file');
  }
  gains.push({
    code: 'no_dna_on_file',
    label: 'No DNA profile on file',
    detail: 'Forensic DNA databases cannot link crime-scene samples to a civil identity.',
  });

  gains.push({
    code: 'no_paper_trail',
    label: 'Unregistered ghost',
    detail: 'No tax roll, civil registry, or known home address lets investigators trace you on paper.',
  });

  // Remove anchored identity files for the player from crime forensic files
  const nextIdentityFiles: Timeline['identityFiles'] = {};
  for (const [personId, recs] of Object.entries(timeline.identityFiles)) {
    if (personId === p.playerId || (p.anchoredPersonId && personId === p.anchoredPersonId)) {
      continue;
    }
    nextIdentityFiles[personId] = recs.map((r) => ({ ...r }));
  }

  let next = branch(timeline, {
    year,
    placeId: targetPlace,
    kind: 'self_erase',
    actorId: p.playerId,
    ...(p.anchoredPersonId !== undefined ? { targetId: p.anchoredPersonId } : {}),
    causeTag: 'player_origin',
    summary: `Erased anchored self in ${year}`,
  });

  next = {
    ...next,
    identityFiles: nextIdentityFiles,
    player: {
      ...next.player,
      selfErased: true,
      selfErasedYear: year,
      records: {
        birthRecord: false,
        bankAccount: false,
        fingerprintsOnFile: false,
        dnaOnFile: false,
        legalIdentity: false,
        passportEligible: false,
        jobReferences: [],
        knownFamilyIds: [],
      },
    },
  };

  const summary = [
    `Erased anchored self in ${year} at ${targetPlace}.`,
    `Lost: ${losses.map((l) => l.label).join('; ') || 'none'}.`,
    `Gained: ${gains.map((g) => g.label).join('; ')}.`,
  ];

  const report: SelfEraseReport = {
    year,
    placeId: targetPlace,
    playerId: p.playerId,
    ...(p.anchoredPersonId !== undefined ? { anchoredPersonId: p.anchoredPersonId } : {}),
    alreadyErased: false,
    erasedRecords,
    losses,
    gains,
    summary,
  };

  return { timeline: next, report };
}
