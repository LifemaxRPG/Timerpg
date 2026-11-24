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
import { arrears, dailyBudget, dailyNeeds, dueOn, lodgingOf } from '../economy/living';
import { classesOf, departures, fare, travelTime } from '../transit';
import { dateOf, dayNumberOf, daylight, holidaysOn, isMarketDay, isPayday, schoolTerm, toShopDate } from '../calendar';
import { apply, openings, shift } from '../jobs';
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
  // living costs (C022, docs/ECONOMY.md)
  lodgingOf,
  dailyNeeds,
  dueOn,
  arrears,
  dailyBudget,
} as const;
(globalThis as Record<string, unknown>).Economy = Economy;

/**
 * Fares and timetables (src/transit, docs/TRANSIT.md) for the phone: `simCall({"method":"transit.fare","args":
 * ["city_bus",2025,1.2]})`. Pure and stateless.
 */
export const Transit = { fare, departures, travelTime, classesOf } as const;
(globalThis as Record<string, unknown>).Transit = Transit;

/**
 * The world calendar (src/calendar, docs/CALENDAR.md) for the phone: `simCall({"method":"calendar.dateOf","args":[0,
 * {"year":2025,"month":5,"day":6}]})`. Pure and stateless; `null` for an invalid date.
 */
export const Calendar = { dateOf, dayNumberOf, holidaysOn, isPayday, isMarketDay, schoolTerm, daylight, toShopDate } as const;
(globalThis as Record<string, unknown>).Calendar = Calendar;

/**
 * Employment (src/jobs, docs/JOBS.md) for the phone: `jobs.openings([{"id":"p","kind":"general"}], 2025, 3, "seed")`,
 * `jobs.apply(opening, applicant)`, `jobs.shift(employment, {"day":4,"outcome":"on_time"})`. Pure and stateless: the
 * game keeps the employment and passes it back after each shift.
 */
export const Jobs = { openings, apply, shift } as const;
(globalThis as Record<string, unknown>).Jobs = Jobs;

const namespaces: Record<string, Record<string, unknown>> = { economy: Economy, transit: Transit, calendar: Calendar, jobs: Jobs };

/**
 * One entry for the phone (SimJs.kt calls a global function with one JSON string): `{"method": "advance", "args":
 * [24]}` calls `TimerpgSim.advance(24)` and returns its JSON string; a non-string argument is passed as JSON text
 * where the method takes JSON (e.g. `act`'s event as an object). `economy.*` methods go to [Economy], `transit.*`
 * to [Transit], `calendar.*` to [Calendar], `jobs.*` to [Jobs].
 */
export function simCall(json: string): string {
  const req = JSON.parse(json) as { method: string; args?: unknown[] };
  const dot = req.method.indexOf('.');
  const space = dot > 0 ? namespaces[req.method.slice(0, dot)] : undefined;
  if (space) {
    const name = req.method.slice(dot + 1);
    if (!Object.prototype.hasOwnProperty.call(space, name)) throw new Error(`${req.method.slice(0, dot)} has no method "${name}"`);
    return JSON.stringify((space[name] as (...a: unknown[]) => unknown)(...(req.args ?? [])) ?? null);
  }
  const fn = (TimerpgSim as Record<string, unknown>)[req.method];
  if (typeof fn !== 'function') throw new Error(`TimerpgSim has no method "${req.method}"`);
  const args = (req.args ?? []).map((a) => (a !== null && typeof a === 'object' ? JSON.stringify(a) : a));
  return (fn as (...a: unknown[]) => string)(...args);
}
(globalThis as Record<string, unknown>).simCall = simCall;

// the bridge's smoke API (simCreate, simRecordCrime, simAdvance, simKnowers): SimJsTest's, until it moves to TimerpgSim
import './smoke';
