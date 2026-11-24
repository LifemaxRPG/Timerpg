import { priceIndex } from '../economy';
import { clamp01 } from '../sim/rng';

const injuries = {
  sprain: { hours: 96, healthLoss: .12, cents: 300 },
  cut: { hours: 72, healthLoss: .09, cents: 225 },
  bruise: { hours: 48, healthLoss: .06, cents: 150 },
} as const;

export type Injury = keyof typeof injuries;
export type Needs = Readonly<{
  hunger: number;
  fatigue: number;
  hygiene: number;
  health: number;
  injuries: readonly { kind: Injury; remainingHours: number; treated: boolean }[];
}>;

export function initialNeeds(): Needs {
  return { hunger: .12, fatigue: .08, hygiene: 1, health: 1, injuries: [] };
}

const rates = {
  idle: [.035, .037, .014],
  walking: [.043, .046, .021],
  running: [.065, .075, .052],
  working: [.044, .05, .026],
  sleeping: [.025, -.12, .007],
} as const;
export type Activity = keyof typeof rates;

export function treatmentCost(injury: Injury, year: number): number | null {
  const index = priceIndex(year);
  const base = priceIndex(1952)!;
  if (index === null || !Object.prototype.hasOwnProperty.call(injuries, injury)) return null;
  return Math.round(injuries[injury].cents * index / base);
}
