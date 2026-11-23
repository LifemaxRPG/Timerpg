import { clamp01, rngFor } from '../sim/rng';
import { affinity, pairKey } from '../sim/social';
import { ageIn, isAlive, type Id, type Person, type World } from '../sim/types';
import {
  BUILD_BANDS,
  CLOTHING_COLORS,
  HAIR_TONES,
  HEIGHT_BANDS,
  VEHICLE_KINDS,
  type BuildBand,
  type ClothingColor,
  type CrimeAccount,
  type CrimeContext,
  type CrimeEvent,
  type CulpritDescription,
  type HairTone,
  type HeightBand,
  type VehicleKind,
} from './types';

export const MAX_GOSSIP_HOPS = 4;
const HOP_ATTEN: readonly number[] = [1.0, 0.58, 0.3364, 0.195112, 0.113165];
const DEFAULT_TOWN = 'town:default';

export type ContactRelation = 'spouse' | 'kin' | 'coworker' | 'friend' | 'travel';

export interface SocialContact {
  id: Id;
  relation: ContactRelation;
  /** Closeness in [0, 1] used to weight gossip probability. */
  closeness: number;
  /** Whether this link naturally bridges different towns (kin & travel). */
  bridgesTowns: boolean;
  /** Precomputed relation * closeness * crossTown scale for O(1) hot-loop checks. */
  weight: number;
}

export interface WorldSocialIndex {
  peopleRef: World['people'];
  bondsRef: World['bonds'];
  defaultTown: string;
  townRosters: Map<string, Id[]>;
  workplaceRosters: Map<string, Id[]>;
  bondNeighbors: Map<Id, Id[]>;
  siblings: Map<Id, Id[]>;
  contactCache: Map<Id, SocialContact[]>;
  /** Cached deterministic `rand(seed, 'wont_talk_police', personId)` per person. */
  refusalRollCache: Map<Id, number>;
  /** Symmetric cache for `affinity(world, a, b)` results within this World snapshot. */
  affinityCache: Map<string, number>;
}

export function cachedAffinity(world: World, idx: WorldSocialIndex, a: Id, b: Id): number {
  const k = pairKey(a, b);
  const hit = idx.affinityCache.get(k);
  if (hit !== undefined) return hit;
  const val = affinity(world, a, b);
  idx.affinityCache.set(k, val);
  return val;
}

const indexCache = new WeakMap<World, WeakMap<object, WorldSocialIndex>>();
const DEFAULT_CTX_KEY = {};

export function getSocialIndex(world: World, ctx?: CrimeContext): WorldSocialIndex {
  const ctxKey: object = ctx ?? DEFAULT_CTX_KEY;
  let byCtx = indexCache.get(world);
  if (!byCtx) {
    byCtx = new WeakMap();
    indexCache.set(world, byCtx);
  }
  const existing = byCtx.get(ctxKey);
  if (
    existing &&
    existing.peopleRef === world.people &&
    existing.bondsRef === world.bonds
  ) {
    return existing;
  }

  const peopleKeys = Object.keys(world.people).sort();
  const bondsKeys = Object.keys(world.bonds);
  const townOf = ctx?.townOf ?? (() => DEFAULT_TOWN);
  const workplaceOf = ctx?.workplaceOf;

  const townRosters = new Map<string, Id[]>();
  const workplaceRosters = new Map<string, Id[]>();
  const childrenByParent = new Map<Id, Id[]>();

  for (let i = 0; i < peopleKeys.length; i++) {
    const id = peopleKeys[i]!;
    const p = world.people[id]!;
    if (!isAlive(p)) continue;

    const town = townOf(id);
    let tr = townRosters.get(town);
    if (!tr) {
      tr = [];
      townRosters.set(town, tr);
    }
    tr.push(id);

    if (workplaceOf) {
      const wp = workplaceOf(id);
      if (wp) {
        let wr = workplaceRosters.get(wp);
        if (!wr) {
          wr = [];
          workplaceRosters.set(wp, wr);
        }
        wr.push(id);
      }
    }

    for (let k = 0; k < p.parents.length; k++) {
      const pid = p.parents[k]!;
      let list = childrenByParent.get(pid);
      if (!list) {
        list = [];
        childrenByParent.set(pid, list);
      }
      list.push(id);
    }
  }

  const siblings = new Map<Id, Id[]>();
  for (const kids of childrenByParent.values()) {
    if (kids.length < 2) continue;
    for (let i = 0; i < kids.length; i++) {
      const a = kids[i]!;
      let set = siblings.get(a);
      for (let j = 0; j < kids.length; j++) {
        if (i === j) continue;
        const b = kids[j]!;
        if (!set) {
          set = [];
          siblings.set(a, set);
        }
        if (!set.includes(b)) set.push(b);
      }
    }
  }

  const bondNeighbors = new Map<Id, Id[]>();
  for (let i = 0; i < bondsKeys.length; i++) {
    const key = bondsKeys[i]!;
    const sep = key.indexOf('|');
    if (sep <= 0) continue;
    const a = key.slice(0, sep);
    const b = key.slice(sep + 1);
    if (!world.people[a] || !world.people[b]) continue;
    let la = bondNeighbors.get(a);
    if (!la) { la = []; bondNeighbors.set(a, la); }
    la.push(b);
    let lb = bondNeighbors.get(b);
    if (!lb) { lb = []; bondNeighbors.set(b, lb); }
    lb.push(a);
  }

  const idx: WorldSocialIndex = {
    peopleRef: world.people,
    bondsRef: world.bonds,
    defaultTown: DEFAULT_TOWN,
    townRosters,
    workplaceRosters,
    bondNeighbors,
    siblings,
    contactCache: new Map(),
    refusalRollCache: new Map(),
    affinityCache: new Map(),
  };
  byCtx.set(ctxKey, idx);
  return idx;
}

function relationWeight(relation: ContactRelation, closeness: number, crossTown: boolean): number {
  let relMult: number;
  if (relation === 'spouse') relMult = 2.4;
  else if (relation === 'kin') relMult = 1.25;
  else if (relation === 'coworker') relMult = 0.72;
  else if (relation === 'travel') relMult = 1.65;
  else relMult = 0.56;
  const crossTownScale = crossTown ? (relation === 'travel' ? 0.85 : 0.55) : 1.0;
  return relMult * (0.28 + 0.72 * clamp01(closeness)) * crossTownScale;
}

/**
 * Lazily computes a person's regular confidants (spouse, kin, coworkers, close friends by `affinity`)
 * in bounded O(1) amortized time rather than O(N^2) across the town.
 */
function baseContactsOf(
  world: World,
  idx: WorldSocialIndex,
  person: Person,
  ctx?: CrimeContext,
): SocialContact[] {
  const hit = idx.contactCache.get(person.id);
  if (hit) return hit;

  const townOf = ctx?.townOf ?? (() => idx.defaultTown);
  const workplaceOf = ctx?.workplaceOf;
  const myTown = townOf(person.id);

  const out: SocialContact[] = [];
  const seen: Id[] = [person.id];

  const add = (id: Id, relation: ContactRelation, closeness: number, bridgesTowns: boolean): void => {
    if (seen.includes(id)) return;
    const target = world.people[id];
    if (!target || !isAlive(target)) return;
    const targetTown = townOf(id);
    const crossTown = targetTown !== myTown;
    if (crossTown && !bridgesTowns) return;
    seen.push(id);
    const c = clamp01(closeness);
    out.push({
      id,
      relation,
      closeness: c,
      bridgesTowns,
      weight: relationWeight(relation, c, crossTown),
    });
  };

  if (person.spouse) add(person.spouse, 'spouse', 1.0, true);
  for (let i = 0; i < person.parents.length; i++) add(person.parents[i]!, 'kin', 0.86, true);
  for (let i = 0; i < person.children.length; i++) add(person.children[i]!, 'kin', 0.84, true);
  const sibs = idx.siblings.get(person.id);
  if (sibs) {
    for (let i = 0; i < sibs.length; i++) add(sibs[i]!, 'kin', 0.74, true);
  }

  const rCircle = rngFor(world.seed, 'social_circle', person.id);

  // Up to 2 coworkers at the same workplace
  if (workplaceOf) {
    const wp = workplaceOf(person.id);
    if (wp) {
      const roster = idx.workplaceRosters.get(wp);
      if (roster && roster.length > 1) {
        const count = Math.min(2, roster.length - 1);
        const start = Math.floor(rCircle() * roster.length);
        let added = 0;
        for (let step = 0; step < roster.length && added < count; step++) {
          const cid = roster[(start + step) % roster.length]!;
          if (seen.includes(cid)) continue;
          const aff = cachedAffinity(world, idx, person.id, cid);
          if (aff < -0.20) continue;
          add(cid, 'coworker', 0.45 + Math.max(0, aff) * 0.35, false);
          added++;
        }
      }
    }
  }

  // Explicit bonded friends in same town
  const bonded = idx.bondNeighbors.get(person.id);
  if (bonded) {
    for (let i = 0; i < bonded.length; i++) {
      const bid = bonded[i]!;
      if (seen.includes(bid)) continue;
      if (townOf(bid) !== myTown) continue;
      const aff = cachedAffinity(world, idx, person.id, bid);
      if (aff > 0.0) add(bid, 'friend', 0.35 + aff * 0.50, false);
    }
  }

  // Up to 4 regular local friends/neighbors inside the same town, weighted by `affinity`
  const roster = idx.townRosters.get(myTown);
  if (roster && roster.length > 1) {
    const maxFriends = Math.min(4, roster.length - 1);
    const probeCount = Math.min(roster.length, maxFriends + 1);
    let added = 0;
    for (let i = 0; i < probeCount && added < maxFriends; i++) {
      const candidateId = roster[Math.floor(rCircle() * roster.length)]!;
      if (seen.includes(candidateId)) continue;
      const aff = cachedAffinity(world, idx, person.id, candidateId);
      if (aff < -0.05) continue;
      add(candidateId, 'friend', 0.22 + Math.max(0, aff + 0.05) * 0.52, false);
      added++;
    }
  }

  idx.contactCache.set(person.id, out);
  return out;
}

function mutateField<T>(r: () => number, current: T, domain: readonly T[]): T | undefined {
  if (r() < 0.55) return undefined;
  const others = domain.filter((v) => v !== current);
  return others[Math.floor(r() * others.length)]!;
}

/**
 * Produces a second-hand retold culprit description from `teller`'s account.
 * Second-hand accounts have strictly lower confidence and can drop or distort details.
 */
export function retellDescription(
  source: CulpritDescription,
  newConfidence: number,
  seed: string,
  crimeId: string,
  tellerId: Id,
  listenerId: Id,
  prng?: () => number,
): CulpritDescription {
  const r = prng ?? rngFor(seed, 'retell_desc', crimeId, tellerId, listenerId);
  const distortProb = clamp01(0.36 * (1 - newConfidence * 0.85));
  const out: CulpritDescription = {};

  if (source.height !== undefined) {
    const v = r() < distortProb ? mutateField<HeightBand>(r, source.height, HEIGHT_BANDS) : source.height;
    if (v !== undefined) out.height = v;
  }
  if (source.build !== undefined) {
    const v = r() < distortProb ? mutateField<BuildBand>(r, source.build, BUILD_BANDS) : source.build;
    if (v !== undefined) out.build = v;
  }
  if (source.hair !== undefined) {
    const v = r() < distortProb ? mutateField<HairTone>(r, source.hair, HAIR_TONES) : source.hair;
    if (v !== undefined) out.hair = v;
  }
  if (source.clothingColor !== undefined) {
    const v = r() < distortProb * 1.15 ? mutateField<ClothingColor>(r, source.clothingColor, CLOTHING_COLORS) : source.clothingColor;
    if (v !== undefined) out.clothingColor = v;
  }
  if (source.vehicle !== undefined) {
    if (r() < distortProb * 0.65) {
      if (r() >= 0.45) {
        const k =
          r() < 0.5
            ? (mutateField<VehicleKind>(r, source.vehicle.kind, VEHICLE_KINDS) ?? source.vehicle.kind)
            : source.vehicle.kind;
        const col =
          r() < 0.5
            ? (mutateField<ClothingColor>(r, source.vehicle.color, CLOTHING_COLORS) ?? source.vehicle.color)
            : source.vehicle.color;
        out.vehicle = { kind: k, color: col };
      }
    } else {
      out.vehicle = { ...source.vehicle };
    }
  }

  return out;
}

export interface GossipResult {
  newAccounts: CrimeAccount[];
  newTold: Array<{ tellerId: Id; listenerId: Id }>;
}

function makeRetoldAccount(
  crimeId: string,
  tellerId: Id,
  listenerId: Id,
  acc: CrimeAccount,
  day: number,
  nextConf: number,
  identified: Id | undefined,
  retoldDesc: CulpritDescription,
  existing: CrimeAccount | undefined,
): CrimeAccount {
  const out: CrimeAccount = {
    crimeId,
    knowerId: listenerId,
    source: 'second_hand',
    isVictim: false,
    hops: acc.hops + 1,
    heardFrom: tellerId,
    dayLearned: day,
    lastRefreshedDay: day,
    confidence: nextConf,
    initialConfidence: nextConf,
    description: retoldDesc,
    reported: existing !== undefined ? existing.reported : false,
    refreshes: 0,
  };
  if (identified !== undefined) out.identifiedCulpritId = identified;
  if (existing?.reportQueuedDay !== undefined) out.reportQueuedDay = existing.reportQueuedDay;
  if (existing?.reportedJurisdiction !== undefined) out.reportedJurisdiction = existing.reportedJurisdiction;
  return out;
}

/**
 * Runs one simulated day of word-of-mouth propagation for a single crime.
 * Uses a single deterministic `rngFor` stream per `(seed, day, crimeId)` over sorted knower ids.
 */
export function spreadWordOfMouthForCrime(
  seed: string,
  world: World,
  crime: CrimeEvent,
  accounts: Record<Id, CrimeAccount>,
  toldPairs: Record<Id, Id[]>,
  day: number,
  ctx?: CrimeContext,
  precomputedIndex?: WorldSocialIndex,
  sortedKnowerIds?: readonly Id[],
): GossipResult {
  const knowerIds = sortedKnowerIds ?? Object.keys(accounts).sort();
  if (knowerIds.length === 0) return { newAccounts: [], newTold: [] };

  const idx = precomputedIndex ?? getSocialIndex(world, ctx);
  const year = ctx?.year ?? world.year;
  const townOf = ctx?.townOf ?? (() => idx.defaultTown);
  const travelTownsOf = ctx?.travelTownsOf;

  const newAccountsMap = new Map<Id, CrimeAccount>();
  const newTold: Array<{ tellerId: Id; listenerId: Id }> = [];

  const sevFactor = 0.14 + 0.86 * Math.pow(clamp01(crime.severity), 0.90);
  const recencyHalfLife = 2.5 + 7.5 * clamp01(crime.severity);
  const maxTellDays = Math.ceil(recencyHalfLife * 3.2);
  const recencyStep = Math.pow(0.5, 1 / recencyHalfLife);
  const recencyTable = new Float64Array(maxTellDays + 1);
  recencyTable[0] = 1.0;
  for (let d = 1; d <= maxTellDays; d++) recencyTable[d] = recencyTable[d - 1]! * recencyStep;

  const rCrime = rngFor(seed, 'gossip_crime_day', day, crime.id);

  for (let i = 0; i < knowerIds.length; i++) {
    const tellerId = knowerIds[i]!;
    const acc = accounts[tellerId]!;
    if (acc.hops >= MAX_GOSSIP_HOPS) continue;
    if (acc.confidence < 0.16) continue;

    const daysSinceLearned = Math.max(0, day - acc.dayLearned);
    if (daysSinceLearned > maxTellDays) continue;

    const teller = world.people[tellerId];
    if (!teller || !isAlive(teller)) continue;
    if (ageIn(teller, year) < 6) continue;

    const recency = recencyTable[daysSinceLearned]!;
    const hopAtten = HOP_ATTEN[acc.hops] ?? 0;
    const baseUrge =
      (0.16 + 0.44 * teller.traits.charm) *
      sevFactor *
      hopAtten *
      recency *
      (0.40 + 0.60 * acc.confidence);
    if (baseUrge < 0.015) continue;

    const nextConf = clamp01(acc.confidence * (0.64 + 0.10 * teller.traits.diligence));
    if (nextConf < 0.08) continue;

    const contacts = baseContactsOf(world, idx, teller, ctx);
    const alreadyTold = toldPairs[tellerId];

    for (let c = 0; c < contacts.length; c++) {
      const contact = contacts[c]!;
      const listenerId = contact.id;
      if (listenerId === crime.culpritId) continue;

      const existing = accounts[listenerId] ?? newAccountsMap.get(listenerId);
      if (existing !== undefined && existing.confidence >= nextConf * 0.95) continue;

      if (rCrime() >= baseUrge * contact.weight) continue;
      if (alreadyTold !== undefined && alreadyTold.includes(listenerId)) continue;

      const listener = world.people[listenerId];
      if (!listener || !isAlive(listener) || ageIn(listener, year) < 6) continue;

      newTold.push({ tellerId, listenerId });

      const retoldDesc = retellDescription(acc.description, nextConf, seed, crime.id, tellerId, listenerId, rCrime);
      let identified: Id | undefined;
      if (acc.identifiedCulpritId && nextConf >= 0.22 && rCrime() < 0.55 + nextConf * 0.42) {
        identified = acc.identifiedCulpritId;
      }

      const candidate = makeRetoldAccount(
        crime.id,
        tellerId,
        listenerId,
        acc,
        day,
        nextConf,
        identified,
        retoldDesc,
        existing,
      );
      const prevNew = newAccountsMap.get(listenerId);
      if (prevNew === undefined || candidate.confidence > prevNew.confidence) {
        newAccountsMap.set(listenerId, candidate);
      }
    }

    if (travelTownsOf) {
      const visited = travelTownsOf(tellerId, day);
      if (visited.length > 0) {
        const tellerTown = townOf(tellerId);
        const wTravel = relationWeight('travel', 0.55, true);
        for (let v = 0; v < visited.length; v++) {
          const destTown = visited[v]!;
          if (destTown === tellerTown) continue;
          const destRoster = idx.townRosters.get(destTown);
          if (!destRoster || destRoster.length === 0) continue;
          const sampleCount = Math.min(4, destRoster.length);
          const rTravel = rngFor(seed, 'travel_contacts', day, tellerId, destTown);
          for (let s = 0; s < sampleCount; s++) {
            const listenerId = destRoster[Math.floor(rTravel() * destRoster.length)]!;
            if (listenerId === tellerId || listenerId === crime.culpritId) continue;

            const existing = accounts[listenerId] ?? newAccountsMap.get(listenerId);
            if (existing !== undefined && existing.confidence >= nextConf * 0.95) continue;

            if (rCrime() >= baseUrge * wTravel) continue;
            if (alreadyTold !== undefined && alreadyTold.includes(listenerId)) continue;

            const listener = world.people[listenerId];
            if (!listener || !isAlive(listener) || ageIn(listener, year) < 6) continue;

            newTold.push({ tellerId, listenerId });

            const retoldDesc = retellDescription(acc.description, nextConf, seed, crime.id, tellerId, listenerId, rCrime);
            let identified: Id | undefined;
            if (acc.identifiedCulpritId && nextConf >= 0.22 && rCrime() < 0.55 + nextConf * 0.42) {
              identified = acc.identifiedCulpritId;
            }

            const candidate = makeRetoldAccount(
              crime.id,
              tellerId,
              listenerId,
              acc,
              day,
              nextConf,
              identified,
              retoldDesc,
              existing,
            );
            const prevNew = newAccountsMap.get(listenerId);
            if (prevNew === undefined || candidate.confidence > prevNew.confidence) {
              newAccountsMap.set(listenerId, candidate);
            }
          }
        }
      }
    }
  }

  return { newAccounts: [...newAccountsMap.values()], newTold };
}
