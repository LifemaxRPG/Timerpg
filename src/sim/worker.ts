// Background worker: generates a town's plan and its whole 1950-2025 history off the main thread,
// so walking into new territory never freezes the game. Messages are plain data (structured clone).
import { planTown, type TownPlan } from '../town/plan';
import { runTimeline } from './simulate';
import type { Intervention, World } from './types';

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

const ctx = self as unknown as { onmessage: ((e: MessageEvent<GenRequest>) => void) | null; postMessage: (m: GenResponse) => void };
ctx.onmessage = (e) => {
  const { id, kind, seed, interventions } = e.data;
  if (kind === 'plan') { ctx.postMessage({ id, plan: planTown(seed) }); return; }
  const t = runTimeline({ seed, endYear: 2025, startYear: 1950, interventions: kind === 'history' ? interventions : [] });
  // Failures are prefixed so the game can tell a rejected action (nobody to kill in that year...) from a done one.
  const notes = t.chronicle.filter((ev) => ev.kind === 'intervention' || ev.kind === 'intervention_failed').map((ev) => `${ev.kind === 'intervention_failed' ? '✗ ' : ''}${ev.year}: ${ev.text}`);
  ctx.postMessage({ id, plan: kind === 'town' ? planTown(seed) : undefined, snapshots: t.snapshots, notes });
};
