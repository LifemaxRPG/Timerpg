import { primePlan, type TownPlan } from '../town/plan';
import type { Intervention, World } from '../sim/types';
import type { GenRequest, GenResponse } from '../sim/worker';

/** Asks a background worker for town plans and histories; results come back as promises. */
export class Generator {
  private worker = new Worker(new URL('../sim/worker.ts', import.meta.url), { type: 'module' });
  private pending = new Map<number, (r: GenResponse) => void>();
  private cached = new Map<string, Promise<TownPlan>>();
  private nextId = 1;

  constructor() {
    this.worker.onmessage = (e: MessageEvent<GenResponse>) => {
      this.pending.get(e.data.id)?.(e.data);
      this.pending.delete(e.data.id);
    };
  }

  private ask(req: Omit<GenRequest, 'id'>): Promise<GenResponse> {
    return new Promise((resolve) => {
      const id = this.nextId++;
      this.pending.set(id, resolve);
      this.worker.postMessage({ id, ...req } satisfies GenRequest);
    });
  }

  /** The town's plan (cached; also primes the main thread's plan cache). */
  plan(seed: string): Promise<TownPlan> {
    let p = this.cached.get(seed);
    if (!p) {
      p = this.ask({ kind: 'plan', seed }).then((r) => { primePlan(seed, r.plan!); return r.plan!; });
      this.cached.set(seed, p);
    }
    return p;
  }

  /** The town's plan and its original history, one snapshot per year from 1950. */
  async town(seed: string): Promise<{ plan: TownPlan; snapshots: World[] }> {
    const r = await this.ask({ kind: 'town', seed });
    primePlan(seed, r.plan!);
    this.cached.set(seed, Promise.resolve(r.plan!));
    return { plan: r.plan!, snapshots: r.snapshots! };
  }

  /** The town's history with the player's interventions applied, plus notes on what each did. */
  async history(seed: string, interventions: Intervention[]): Promise<{ snapshots: World[]; notes: string[] }> {
    const r = await this.ask({ kind: 'history', seed, interventions });
    return { snapshots: r.snapshots!, notes: r.notes ?? [] };
  }
}
