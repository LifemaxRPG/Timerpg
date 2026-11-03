import GenWorker from '../sim/worker.ts?worker&inline';
import { generate, type GenRequest, type GenResponse } from '../sim/generate';
import { primePlan, type TownPlan } from '../town/plan';
import type { Intervention, World } from '../sim/types';

/**
 * Asks for town plans and histories; results come back as promises. Generation runs in a background
 * worker (embedded in the page, so it also works from a single saved HTML file). If the browser will not
 * start the worker (some file:// / content:// contexts), it quietly falls back to the main thread.
 */
export class Generator {
  private worker: Worker | null = null;
  private pending = new Map<number, { req: GenRequest; resolve: (r: GenResponse) => void }>();
  private cached = new Map<string, Promise<TownPlan>>();
  private nextId = 1;
  private heard = false;

  constructor() {
    try {
      this.worker = new GenWorker();
      this.worker.onmessage = (e: MessageEvent<GenResponse>) => {
        this.heard = true;
        const p = this.pending.get(e.data.id);
        this.pending.delete(e.data.id);
        p?.resolve(e.data);
      };
      this.worker.onerror = () => this.fallBack();
      // A worker that never answers (blocked silently) is treated like one that failed.
      window.setTimeout(() => { if (!this.heard && this.pending.size) this.fallBack(); }, 12000);
    } catch {
      this.worker = null;
    }
  }

  /** Whether generation is running on the main thread (no worker available). */
  get onMainThread(): boolean {
    return this.worker === null;
  }

  private fallBack(): void {
    if (!this.worker) return;
    this.worker.terminate();
    this.worker = null;
    for (const p of this.pending.values()) this.runHere(p.req, p.resolve);
  }

  private runHere(req: GenRequest, resolve: (r: GenResponse) => void): void {
    window.setTimeout(() => { this.pending.delete(req.id); resolve(generate(req)); }, 0);
  }

  private ask(req: Omit<GenRequest, 'id'>): Promise<GenResponse> {
    return new Promise((resolve) => {
      const full: GenRequest = { id: this.nextId++, ...req };
      this.pending.set(full.id, { req: full, resolve });
      if (this.worker) this.worker.postMessage(full);
      else this.runHere(full, resolve);
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
