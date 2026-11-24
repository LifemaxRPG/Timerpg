import {
  act,
  advance,
  arrest,
  attitude,
  create,
  load,
  policeView,
  save,
  spotted,
} from './facade';
import * as economy from '../economy';
import { fenceDeal, fenceLeads, fenceRisk, townFences } from '../economy/fence';
import type {
  AdvanceOptions,
  ArrestInput,
  LoopActionEvent,
  LoopInit,
  LoopSim,
  OfficerSightingInput,
} from './types';

let activeSim: LoopSim | null = null;

function requireActive(): LoopSim {
  if (!activeSim) {
    throw new Error('TimerpgSim is not initialized: call init(initJson) or load(savedJson) first');
  }
  return activeSim;
}

/** Pure stateless JSON-in / JSON-out entry points. */
export function createJson(initJson: string): string {
  const init = JSON.parse(initJson) as LoopInit;
  return JSON.stringify(create(init));
}

export function actJson(simJson: string, eventJson: string): string {
  const sim = load(simJson);
  const event = JSON.parse(eventJson) as LoopActionEvent;
  return JSON.stringify(act(sim, event));
}

export function advanceJson(simJson: string, hours: number, optionsJson?: string): string {
  const sim = load(simJson);
  const options = optionsJson ? (JSON.parse(optionsJson) as AdvanceOptions) : undefined;
  return JSON.stringify(advance(sim, hours, options));
}

export function attitudeJson(simJson: string, personId: string): string {
  const sim = load(simJson);
  return JSON.stringify(attitude(sim, personId));
}

export function policeViewJson(simJson: string, jurisdictionId: string): string {
  const sim = load(simJson);
  return JSON.stringify(policeView(sim, jurisdictionId));
}

export function spottedJson(simJson: string, officerId: string, sightingJson: string): string {
  const sim = load(simJson);
  const sighting = JSON.parse(sightingJson) as OfficerSightingInput;
  return JSON.stringify(spotted(sim, officerId, sighting));
}

export function arrestJson(simJson: string, inputJson: string): string {
  const sim = load(simJson);
  const input = JSON.parse(inputJson) as ArrestInput;
  return JSON.stringify(arrest(sim, input));
}

/**
 * Stateful QuickJS-ng bridge: keeps the active `LoopSim` inside the JS engine so the
 * Android native layer only sends small JSON events and receives small JSON deltas
 * instead of serializing the full world on every call.
 */
export const TimerpgSim = {
  init(initJson: string): string {
    const init = JSON.parse(initJson) as LoopInit;
    activeSim = create(init);
    return JSON.stringify({
      ok: true,
      day: activeSim.currentDay,
      hour: activeSim.currentHour,
      year: activeSim.year,
      peopleCount: Object.keys(activeSim.peopleMeta).length,
      officerCount: Object.keys(activeSim.officers).length,
    });
  },

  act(eventJson: string): string {
    const current = requireActive();
    const event = JSON.parse(eventJson) as LoopActionEvent;
    const res = act(current, event);
    activeSim = res.sim;
    const { sim: _omit, ...delta } = res;
    return JSON.stringify(delta);
  },

  advance(hours: number, optionsJson?: string): string {
    const current = requireActive();
    const options = optionsJson ? (JSON.parse(optionsJson) as AdvanceOptions) : undefined;
    const res = advance(current, hours, options);
    activeSim = res.sim;
    const { sim: _omit, ...delta } = res;
    return JSON.stringify(delta);
  },

  attitude(personId: string): string {
    return JSON.stringify(attitude(requireActive(), personId));
  },

  policeView(jurisdictionId: string): string {
    return JSON.stringify(policeView(requireActive(), jurisdictionId));
  },

  spotted(officerId: string, sightingJson: string): string {
    const sighting = JSON.parse(sightingJson) as OfficerSightingInput;
    return JSON.stringify(spotted(requireActive(), officerId, sighting));
  },

  arrest(inputJson: string): string {
    const current = requireActive();
    const input = JSON.parse(inputJson) as ArrestInput;
    const res = arrest(current, input);
    activeSim = res.sim;
    const { sim: _omit, ...delta } = res;
    return JSON.stringify(delta);
  },

  save(): string {
    return save(requireActive());
  },

  load(savedJson: string): string {
    activeSim = load(savedJson);
    return JSON.stringify({
      ok: true,
      day: activeSim.currentDay,
      hour: activeSim.currentHour,
      year: activeSim.year,
      peopleCount: Object.keys(activeSim.peopleMeta).length,
      officerCount: Object.keys(activeSim.officers).length,
    });
  },

  createJson,
  actJson,
  advanceJson,
  attitudeJson,
  policeViewJson,
  spottedJson,
  arrestJson,
};

// Expose globally for QuickJS-ng (`esbuild --format=iife`)
(globalThis as Record<string, unknown>).TimerpgSim = TimerpgSim;

/**
 * One entry for the phone (SimJs.kt calls a global function with one JSON string): `{"method": "advance", "args":
 * [24]}` calls `TimerpgSim.advance(24)` and returns its JSON string; a non-string argument is passed as JSON text
 * where the method takes JSON (e.g. `act`'s event as an object).
 */
/**
 * The era economy (src/economy, docs/ECONOMY.md) for the phone: `simCall({"method":"economy.stockOf","args":["general",
 * 1952,"gamma"]})`. Pure and stateless; every result is JSON text (`null` for an unknown item, job or year).
 */
export const Economy = {
  priceIn: economy.priceIn,
  priceIndex: economy.priceIndex,
  stockOf: economy.stockOf,
  openStock: economy.openStock,
  take: economy.take,
  restock: economy.restock,
  quantityOf: economy.quantityOf,
  wage: economy.wage,
  pawnOffer: economy.pawnOffer,
  pawnPrice: economy.pawnPrice,
  fenceOffer: economy.fenceOffer,
  formatMoney: economy.formatMoney,
  hoursOf: economy.hoursOf,
  isOpen: economy.isOpen,
  staffed: economy.staffed,
  townFences,
  fenceLeads,
  fenceDeal,
  fenceRisk,
} as const;
(globalThis as Record<string, unknown>).Economy = Economy;

/**
 * One entry for the phone (SimJs.kt calls a global function with one JSON string): `{"method": "advance", "args":
 * [24]}` calls `TimerpgSim.advance(24)` and returns its JSON string; a non-string argument is passed as JSON text
 * where the method takes JSON (e.g. `act`'s event as an object). `economy.*` methods go to [Economy].
 */
export function simCall(json: string): string {
  const req = JSON.parse(json) as { method: string; args?: unknown[] };
  if (req.method.startsWith('economy.')) {
    const name = req.method.slice('economy.'.length);
    if (!Object.prototype.hasOwnProperty.call(Economy, name)) throw new Error(`Economy has no method "${name}"`);
    return JSON.stringify((Economy as Record<string, (...a: unknown[]) => unknown>)[name]!(...(req.args ?? [])) ?? null);
  }
  const fn = (TimerpgSim as Record<string, unknown>)[req.method];
  if (typeof fn !== 'function') throw new Error(`TimerpgSim has no method "${req.method}"`);
  const args = (req.args ?? []).map((a) => (a !== null && typeof a === 'object' ? JSON.stringify(a) : a));
  return (fn as (...a: unknown[]) => string)(...args);
}
(globalThis as Record<string, unknown>).simCall = simCall;

// the bridge's smoke API (simCreate, simRecordCrime, simAdvance, simKnowers): SimJsTest's, until it moves to TimerpgSim
import './smoke';
