import { primePlan, type TownPlan } from '../town/plan';
import type { World } from '../sim/types';
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

  private ask(kind: GenRequest['kind'], seed: string): Promise<GenResponse> {
    return new Promise((resolve) => {
      const id = this.nextId++;
      this.pending.set(id, resolve);
      this.worker.postMessage({ id, kind, seed } satisfies GenRequest);
    });
  }

  /** The town's plan (cached; also primes the main thread's plan cache). */
  plan(seed: string): Promise<TownPlan> {
    let p = this.cached.get(seed);
    if (!p) {
      p = this.ask('plan', seed).then((r) => { primePlan(seed, r.plan); return r.plan; });
      this.cached.set(seed, p);
    }
    return p;
  }

  /** The town's plan and its history, one snapshot per year from 1950. */
  async town(seed: string): Promise<{ plan: TownPlan; snapshots: World[] }> {
    const r = await this.ask('town', seed);
    primePlan(seed, r.plan);
    this.cached.set(seed, Promise.resolve(r.plan));
    return { plan: r.plan, snapshots: r.snapshots! };
  }
}
