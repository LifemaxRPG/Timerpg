import { planTown, type TownPlan } from '../town/plan';
import { runTimeline } from './simulate';
import type { Intervention, World } from './types';

// The work a generation request asks for. It runs in a Web Worker when one is available, and on the
// main thread (a little slower, with small hitches) when a browser will not start one.
export interface GenRequest {
  id: number;
  /** plan: just the plan. town: plan + original history. history: only a history, with the player's interventions applied. */
  kind: 'plan' | 'town' | 'history';
  seed: string;
  interventions?: Intervention[];
}
export interface GenResponse {
  id: number;
  plan?: TownPlan;
  snapshots?: World[];
  /** What the interventions did (or why they failed), from the chronicle. */
  notes?: string[];
}

export function generate(req: GenRequest): GenResponse {
  const { id, kind, seed, interventions } = req;
  if (kind === 'plan') return { id, plan: planTown(seed) };
  const t = runTimeline({ seed, endYear: 2025, startYear: 1950, interventions: kind === 'history' ? interventions : [] });
  // Failures are prefixed so the game can tell a rejected action (nobody to kill that year...) from a done one.
  const notes = t.chronicle
    .filter((ev) => ev.kind === 'intervention' || ev.kind === 'intervention_failed')
    .map((ev) => `${ev.kind === 'intervention_failed' ? '✗ ' : ''}${ev.year}: ${ev.text}`);
  return { id, plan: kind === 'town' ? planTown(seed) : undefined, snapshots: t.snapshots, notes };
}
