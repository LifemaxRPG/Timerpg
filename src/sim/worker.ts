// Background worker: generates a town's plan and its whole 1950-2025 history off the main thread,
// so walking into new territory never freezes the game. Messages are plain data (structured clone).
import { planTown, type TownPlan } from '../town/plan';
import { runTimeline } from './simulate';
import type { World } from './types';

export interface GenRequest { id: number; kind: 'plan' | 'town'; seed: string }
export interface GenResponse { id: number; plan: TownPlan; snapshots?: World[] }

const ctx = self as unknown as { onmessage: ((e: MessageEvent<GenRequest>) => void) | null; postMessage: (m: GenResponse) => void };
ctx.onmessage = (e) => {
  const { id, kind, seed } = e.data;
  const plan = planTown(seed);
  if (kind === 'plan') ctx.postMessage({ id, plan });
  else ctx.postMessage({ id, plan, snapshots: runTimeline({ seed, endYear: 2025, startYear: 1950 }).snapshots });
};
