import { detectTimeTravelAnomalies } from '../crime';
import { inspectCarriedItems } from './anchoring';
import { presentAt } from './cache';
import { caseStatusAt } from './evidence';
import type { LooseThread, Timeline } from './types';

/**
 * Collects all paradoxes ("loose threads") on `timeline` as plain facts for dialogue and the journal.
 *
 * Under Rule 1 (travellers are unanchored), interventions that remove the cause of the player's own
 * trip are NEVER refused — they are allowed and surfaced here as loose threads.
 */
export function looseThreads(timeline: Timeline, referenceYear?: number): LooseThread[] {
  const year = referenceYear ?? timeline.player.originYear;
  const simYear = Math.max(timeline.startYear, year);
  const threads: LooseThread[] = [];

  // 1. Causal-anchor interventions (e.g. professor's lab never built, time machine prevented, trip cause removed)
  for (const iv of timeline.interventions) {
    if (iv.kind === 'self_erase') continue; // reported explicitly under `self_erased` below
    const isCausalDisruption =
      iv.causeTag !== undefined ||
      iv.kind === 'prevent_building' ||
      (iv.buildingLabel !== undefined && /lab|machine/i.test(iv.buildingLabel));

    if (!isCausalDisruption) continue;

    if (iv.causeTag === 'professors_lab' || (iv.buildingLabel && /professor.*lab/i.test(iv.buildingLabel))) {
      const label = iv.buildingLabel ?? "the professor's lab";
      threads.push({
        id: `thread:cause:${iv.id}`,
        kind: 'cause_removed',
        year: iv.year,
        placeId: iv.placeId,
        allowed: true,
        fact: `${label} was never built in ${iv.year}, yet you travelled from it — allowed because he built it as an unanchored man.`,
        journalLine: `Loose thread (${iv.year}): ${label} does not exist in this history, though you remember stepping out of it.`,
        dialogueHook: `Nobody in town remembers ${label} ever standing there.`,
        relatedIds: [iv.id],
      });
    } else if (iv.causeTag === 'time_machine') {
      threads.push({
        id: `thread:cause:${iv.id}`,
        kind: 'cause_removed',
        year: iv.year,
        placeId: iv.placeId,
        allowed: true,
        fact: `The intervention in ${iv.year} prevented the time machine's origin in this timeline, yet you remain unanchored.`,
        journalLine: `Loose thread (${iv.year}): You severed the machine's own origin without erasing yourself.`,
        dialogueHook: `You carry memories of a machine no local engineer ever built.`,
        relatedIds: [iv.id],
      });
    } else if (iv.causeTag === 'professors_letter') {
      threads.push({
        id: `thread:cause:${iv.id}`,
        kind: 'cause_removed',
        year: iv.year,
        placeId: iv.placeId,
        allowed: true,
        fact: `The professor's letter that summoned you never reached you in this timeline (${iv.year}), yet you are already here.`,
        journalLine: `Loose thread (${iv.year}): The letter that started your journey was never sent in this history.`,
        dialogueHook: `The summons that brought you to the capital has no sender in this world.`,
        relatedIds: [iv.id],
      });
    } else {
      const desc = iv.buildingLabel ?? iv.summary ?? `${iv.kind} in ${iv.year}`;
      threads.push({
        id: `thread:cause:${iv.id}`,
        kind: 'cause_removed',
        year: iv.year,
        placeId: iv.placeId,
        allowed: true,
        fact: `Intervention "${desc}" (${iv.year}) disrupted a cause of your trip — allowed because travellers are unanchored.`,
        journalLine: `Loose thread (${iv.year}): ${desc} changed the path that brought you here.`,
        dialogueHook: `History no longer leads to how you arrived.`,
        relatedIds: [iv.id],
      });
    }
  }

  // 2. Self-erasure loose thread (Rule 3)
  if (timeline.player.selfErased) {
    const y = timeline.player.selfErasedYear ?? timeline.startYear;
    threads.push({
      id: `thread:self_erased:${timeline.player.playerId}`,
      kind: 'self_erased',
      year: y,
      placeId: timeline.player.homePlaceId,
      allowed: true,
      fact: `You erased your own anchored self in ${y}: you continue to exist unanchored with no birth record, family, or past in this timeline.`,
      journalLine: `Loose thread (${y}): You outlived your own history — no record says you were ever born.`,
      dialogueHook: `No clerk, bank, or relative in town can find any trace of who you are.`,
      relatedIds: [timeline.player.playerId],
    });
  }

  // 3. Orphan carried items (Rule 1 — e.g. coffee receipt from an erased grandson)
  const itemStatuses = inspectCarriedItems(timeline, simYear);
  for (const st of itemStatuses) {
    if (!st.anachronisticProvenance) continue;
    const missing = !st.makerExists
      ? `${st.item.makerName ?? st.item.makerId} never existed`
      : `${st.item.businessName ?? st.item.businessId} was already gone`;
    threads.push({
      id: `thread:item:${st.item.id}`,
      kind: 'orphan_item',
      year: st.item.acquiredYear,
      placeId: st.item.acquiredPlaceId,
      allowed: true,
      fact: `You still carry "${st.item.label}" from ${st.item.acquiredYear}, even though ${missing} in this timeline.`,
      journalLine: `Loose thread (${st.item.acquiredYear}): "${st.item.label}" is in your pocket from a history that no longer happened.`,
      dialogueHook: `If you show someone "${st.item.label}", they won't recognize the name printed on it.`,
      relatedIds: [st.item.id, ...(st.item.makerId ? [st.item.makerId] : []), ...(st.item.businessId ? [st.item.businessId] : [])],
    });
  }

  // 4. Erased crime memories & chronology anomalies
  for (const crimeId of Object.keys(timeline.crimes).sort()) {
    const crime = timeline.crimes[crimeId]!;
    const status = caseStatusAt(timeline, crimeId, Math.max(simYear, crime.year));
    if (status.status === 'never_happened') {
      threads.push({
        id: `thread:erased_crime:${crime.id}`,
        kind: 'erased_crime_memory',
        year: crime.year,
        placeId: crime.placeId,
        allowed: true,
        fact: `You remember committing ${crime.kind} in ${crime.year}, but its target was erased beforehand so it never happened in this timeline.`,
        journalLine: `Loose thread (${crime.year}): Only you remember the ${crime.kind} — history has no record of it.`,
        dialogueHook: `Nobody in ${crime.placeId} has ever heard of that ${crime.kind}.`,
        relatedIds: [crime.id, ...(crime.victimId ? [crime.victimId] : [])],
      });
    }

    if (crime.culpritId === timeline.player.playerId && !timeline.player.selfErased) {
      for (const trace of crime.traces) {
        if (trace.destroyed) continue;
        const anomalies = detectTimeTravelAnomalies({
          traceId: trace.id,
          crimeId: crime.id,
          traceKind: trace.kind,
          personId: crime.culpritId,
          traceYear: trace.createdYear,
          birthYear: timeline.player.birthYear,
        });
        for (const a of anomalies) {
          threads.push({
            id: `thread:anomaly:${crime.id}:${trace.id}:${a.kind}`,
            kind: 'chronology_anomaly',
            year: trace.createdYear,
            placeId: trace.placeId,
            allowed: true,
            fact: `Forensic paradox: ${a.summary}.`,
            journalLine: `Loose thread (${trace.createdYear}): ${a.summary}.`,
            dialogueHook: `An investigator who matches that ${trace.kind} will find it predates your birth by ${a.yearsImpossible} years.`,
            relatedIds: [crime.id, trace.id],
          });
        }
      }
    }
  }

  // 5. Indirect lineage paradox (anchored player person erased by killing an ancestor, without calling selfErase)
  if (!timeline.player.selfErased && timeline.player.anchoredPersonId) {
    const snap = presentAt(timeline, simYear);
    if (snap.changes.erasedPeople.some((p) => p.id === timeline.player.anchoredPersonId)) {
      threads.push({
        id: `thread:lineage:${timeline.player.anchoredPersonId}`,
        kind: 'lineage_paradox',
        year: timeline.player.birthYear,
        placeId: timeline.player.homePlaceId,
        allowed: true,
        fact: `Your anchored self (${timeline.player.anchoredPersonId}) was never born in this timeline, yet you remain unanchored.`,
        journalLine: `Loose thread (${timeline.player.birthYear}): Your family line broke before your birth, leaving you outside history.`,
        dialogueHook: `Your own family name stopped a generation early in this town.`,
        relatedIds: [timeline.player.anchoredPersonId],
      });
    }
  }

  threads.sort((a, b) => a.year - b.year || a.id.localeCompare(b.id));
  return threads;
}
