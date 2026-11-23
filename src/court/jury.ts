import type { CrimeState, PoliceCase } from '../crime/types';
import { clamp01, rngFor } from '../sim/rng';
import { affinity, pairKey } from '../sim/social';
import { ageIn, fullName, isAlive, type Id, type Person, type World } from '../sim/types';
import type {
  DefenseLawyer,
  JurorCandidate,
  JurySelectionResult,
  SeatedJuror,
  StrikeRecord,
  VoirDireFact,
} from './types';

function areDirectKin(a: Person, b: Person): boolean {
  if (a.spouse === b.id || b.spouse === a.id) return true;
  if (a.parents.includes(b.id) || b.parents.includes(a.id)) return true;
  if (a.children.includes(b.id) || b.children.includes(a.id)) return true;
  if (a.parents.length > 0 && a.parents.some((p) => b.parents.includes(p))) return true;
  return false;
}

export interface SelectJuryParams {
  seed: string;
  world: World;
  crimeState: CrimeState;
  policeCase: PoliceCase;
  defendantId: Id;
  year: number;
  lawyer: DefenseLawyer;
  attempt?: number;
  judgeId?: Id;
  prosecutorId?: Id;
  prosecutionStrikes?: number;
  eligibleJurorIds?: readonly Id[];
  jurySize?: number;
}

/**
 * Evaluates voir-dire facts for every eligible adult in the jurisdiction, executes
 * for-cause strikes (kin, friends of victim/defendant, fixed gossip bias) and
 * peremptory strikes by defense & prosecution, and seats 12 jurors deterministically.
 */
export function selectJury(params: SelectJuryParams): JurySelectionResult {
  const {
    seed,
    world,
    crimeState,
    policeCase,
    defendantId,
    year,
    lawyer,
    judgeId,
    prosecutorId,
  } = params;
  const attempt = params.attempt ?? 1;
  const jurySize = params.jurySize ?? 12;
  const prosecutionStrikes = params.prosecutionStrikes ?? 3;

  const crime = crimeState.crimes[policeCase.crimeId];
  const victimId = crime?.victimId;
  const defendant = world.people[defendantId];
  const victim = victimId ? world.people[victimId] : undefined;
  const ledgerForCrime = crimeState.ledger[policeCase.crimeId] ?? {};

  // Exclude courtroom officers, parties, and direct witnesses/reporters of this crime
  const excluded = new Set<Id>([defendantId]);
  if (victimId) excluded.add(victimId);
  if (judgeId) excluded.add(judgeId);
  if (prosecutorId) excluded.add(prosecutorId);
  excluded.add(lawyer.id);
  for (let i = 0; i < policeCase.reports.length; i++) {
    excluded.add(policeCase.reports[i]!.reporterId);
  }
  for (const [pid, acc] of Object.entries(ledgerForCrime)) {
    if (acc.source === 'first_hand') excluded.add(pid);
  }

  const rawIds = params.eligibleJurorIds
    ? [...params.eligibleJurorIds]
    : Object.keys(world.people);
  rawIds.sort();

  const pool: Person[] = [];
  for (let i = 0; i < rawIds.length; i++) {
    const pid = rawIds[i]!;
    if (excluded.has(pid)) continue;
    const p = world.people[pid];
    if (!p || !isAlive(p)) continue;
    if (ageIn(p, year) < 18) continue;
    pool.push(p);
  }

  // Deterministic Fisher-Yates shuffle of the venire pool per (seed, crimeId, attempt)
  const rng = rngFor(seed, 'jury_venire', policeCase.crimeId, attempt);
  for (let i = pool.length - 1; i > 0; i--) {
    const j = Math.floor(rng() * (i + 1));
    const tmp = pool[i]!;
    pool[i] = pool[j]!;
    pool[j] = tmp;
  }

  const candidates: Array<{
    cand: JurorCandidate;
    initialBias: number;
    forCauseReason?: string;
  }> = [];

  for (let i = 0; i < pool.length; i++) {
    const p = pool[i]!;
    const facts: VoirDireFact[] = [];
    let bias = 0;

    const isVictimKin = victim !== undefined && areDirectKin(p, victim);
    const victimAff = victim ? affinity(world, p.id, victim.id) : 0;
    const victimExplicitBond = victim ? (world.bonds[pairKey(p.id, victim.id)] ?? 0) : 0;

    const isDefendantKin = defendant !== undefined && areDirectKin(p, defendant);
    const defAff = defendant ? affinity(world, p.id, defendant.id) : 0;
    const defExplicitBond = defendant ? (world.bonds[pairKey(p.id, defendant.id)] ?? 0) : 0;

    if (isVictimKin) {
      facts.push({
        kind: 'victim_kin',
        summary: `Direct relative of victim ${fullName(victim!)}`,
        lean: 0.45,
      });
      bias += 0.35;
    } else if (victim && (victimAff >= 0.25 || victimExplicitBond >= 0.20)) {
      facts.push({
        kind: 'victim_friend',
        summary: `Personally knows victim ${fullName(victim)} (affinity ${victimAff.toFixed(2)})`,
        lean: 0.28,
      });
      bias += 0.22;
    }

    if (isDefendantKin) {
      facts.push({
        kind: 'defendant_kin',
        summary: `Direct relative of defendant ${fullName(defendant!)}`,
        lean: -0.45,
      });
      bias -= 0.35;
    } else if (defendant && (defAff >= 0.25 || defExplicitBond >= 0.20)) {
      facts.push({
        kind: 'defendant_friend',
        summary: `Personally knows defendant ${fullName(defendant)} (affinity ${defAff.toFixed(2)})`,
        lean: -0.28,
      });
      bias -= 0.22;
    }

    const gossipAcc = ledgerForCrime[p.id];
    const knowsAboutCase = gossipAcc !== undefined;
    const gossipConfidence = gossipAcc?.confidence ?? 0;
    if (gossipAcc) {
      const teller = gossipAcc.heardFrom ? world.people[gossipAcc.heardFrom] : undefined;
      facts.push({
        kind: 'prior_gossip',
        summary: teller
          ? `Heard talk about the case from ${fullName(teller)} (confidence ${Math.round(gossipConfidence * 100)}%)`
          : `Heard town rumors about the case (confidence ${Math.round(gossipConfidence * 100)}%)`,
        lean: 0.14 + gossipConfidence * 0.18,
      });
      bias += 0.08 + gossipConfidence * 0.16;
    }

    if (defendant) {
      if (defendant.reputation <= -0.25) {
        facts.push({
          kind: 'reputation_view',
          summary: `Aware of defendant's poor local reputation (${defendant.reputation.toFixed(2)})`,
          lean: 0.08,
        });
        bias += 0.06;
      } else if (defendant.reputation >= 0.35) {
        facts.push({
          kind: 'reputation_view',
          summary: `Respects defendant's good standing in town (${defendant.reputation.toFixed(2)})`,
          lean: -0.07,
        });
        bias -= 0.05;
      }
    }

    if (p.traits.diligence >= 0.72) {
      facts.push({
        kind: 'trait_note',
        summary: 'Methodical and evidence-focused juror',
        lean: 0,
      });
    } else if (p.traits.charm >= 0.75) {
      facts.push({
        kind: 'trait_note',
        summary: 'Empathetic and attentive to courtroom testimony',
        lean: -0.03,
      });
    }

    let forCauseReason: string | undefined;
    if (isVictimKin) {
      forCauseReason = 'Direct relative of the victim (conflict of interest)';
    } else if (isDefendantKin) {
      forCauseReason = 'Direct relative of the defendant (conflict of interest)';
    } else if (victim && (victimAff >= 0.30 || victimExplicitBond >= 0.20)) {
      forCauseReason = `Close personal relationship with victim ${fullName(victim)}`;
    } else if (defendant && (defAff >= 0.35 || defExplicitBond >= 0.25)) {
      forCauseReason = `Close personal relationship with defendant ${fullName(defendant)}`;
    } else if (knowsAboutCase && gossipConfidence >= 0.60) {
      forCauseReason = 'Formed strong pre-trial opinion from detailed town gossip';
    }

    const cand: JurorCandidate = {
      personId: p.id,
      name: fullName(p),
      age: ageIn(p, year),
      traits: { ...p.traits },
      isVictimKin,
      victimAffinity: Math.round(victimAff * 1000) / 1000,
      isDefendantKin,
      defendantAffinity: Math.round(defAff * 1000) / 1000,
      knowsAboutCase,
      gossipConfidence: Math.round(gossipConfidence * 1000) / 1000,
      ...(gossipAcc?.heardFrom ? { heardFrom: gossipAcc.heardFrom } : {}),
      facts,
    };

    candidates.push({
      cand,
      initialBias: Math.round(Math.max(-0.35, Math.min(0.35, bias)) * 1000) / 1000,
      ...(forCauseReason ? { forCauseReason } : {}),
    });
  }

  const struck: StrikeRecord[] = [];
  const afterCause: typeof candidates = [];

  for (let i = 0; i < candidates.length; i++) {
    const item = candidates[i]!;
    if (item.forCauseReason) {
      struck.push({
        personId: item.cand.personId,
        name: item.cand.name,
        struckBy: 'for_cause',
        reason: item.forCauseReason,
        facts: item.cand.facts,
      });
    } else {
      afterCause.push(item);
    }
  }

  // Peremptory strikes (only while keeping enough candidates to seat 12 if available)
  const struckSet = new Set<Id>();

  // 1. Defense strikes most pro-prosecution remaining candidates
  const defCandidates = afterCause
    .slice()
    .sort((a, b) => b.initialBias - a.initialBias || (a.cand.personId < b.cand.personId ? -1 : 1));
  let defStrikesLeft = lawyer.peremptoryStrikes;
  for (let i = 0; i < defCandidates.length && defStrikesLeft > 0; i++) {
    if (afterCause.length - struckSet.size <= jurySize) break;
    const item = defCandidates[i]!;
    if (item.initialBias <= 0.02 && !item.cand.knowsAboutCase && item.cand.victimAffinity <= 0.12) {
      break;
    }
    struckSet.add(item.cand.personId);
    defStrikesLeft--;
    struck.push({
      personId: item.cand.personId,
      name: item.cand.name,
      struckBy: 'defense',
      reason: item.cand.knowsAboutCase
        ? 'Defense peremptory strike: exposed to pre-trial gossip'
        : item.cand.victimAffinity > 0.12
          ? 'Defense peremptory strike: sympathetic toward victim'
          : 'Defense peremptory strike: unfavorable pre-trial lean',
      facts: item.cand.facts,
    });
  }

  // 2. Prosecution strikes most pro-defense remaining candidates
  const prosCandidates = afterCause
    .filter((item) => !struckSet.has(item.cand.personId))
    .sort((a, b) => a.initialBias - b.initialBias || (a.cand.personId < b.cand.personId ? -1 : 1));
  let prosStrikesLeft = prosecutionStrikes;
  for (let i = 0; i < prosCandidates.length && prosStrikesLeft > 0; i++) {
    if (afterCause.length - struckSet.size <= jurySize) break;
    const item = prosCandidates[i]!;
    if (item.initialBias >= -0.02 && item.cand.defendantAffinity <= 0.12) break;
    struckSet.add(item.cand.personId);
    prosStrikesLeft--;
    struck.push({
      personId: item.cand.personId,
      name: item.cand.name,
      struckBy: 'prosecution',
      reason:
        item.cand.defendantAffinity > 0.12
          ? 'Prosecution peremptory strike: sympathetic toward defendant'
          : 'Prosecution peremptory strike: defense-leaning juror',
      facts: item.cand.facts,
    });
  }

  const seated: SeatedJuror[] = [];
  for (let i = 0; i < afterCause.length && seated.length < jurySize; i++) {
    const item = afterCause[i]!;
    if (struckSet.has(item.cand.personId)) continue;
    seated.push({
      seat: seated.length + 1,
      personId: item.cand.personId,
      name: item.cand.name,
      age: item.cand.age,
      traits: item.cand.traits,
      victimAffinity: item.cand.victimAffinity,
      defendantAffinity: item.cand.defendantAffinity,
      knowsAboutCase: item.cand.knowsAboutCase,
      gossipConfidence: item.cand.gossipConfidence,
      initialBias: clamp01(item.initialBias + 0.5) - 0.5,
      facts: item.cand.facts,
    });
  }

  return {
    attempt,
    poolSize: pool.length,
    seated,
    struck,
  };
}
