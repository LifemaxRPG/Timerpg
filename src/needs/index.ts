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
  ill: boolean;
  injuries: readonly { kind: Injury; remainingHours: number; treated: boolean }[];
}>;

export function initialNeeds(): Needs {
  return { hunger: .12, fatigue: .08, hygiene: 1, health: 1, ill: false, injuries: [] };
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

function advance(state: Needs, hours: number, activity: Activity, recovery = 1): Needs {
  if (!Number.isFinite(hours) || hours <= 0 || !Object.prototype.hasOwnProperty.call(rates, activity)) return state;
  const [hungerRate, fatigueRate, dirtRate] = rates[activity];
  const hunger = clamp01(state.hunger + hours * hungerRate);
  const fatigue = clamp01(state.fatigue + hours * fatigueRate * recovery);
  const starving = Math.max(0, hours - Math.max(0, (.9 - state.hunger) / hungerRate));
  const exhausted = fatigueRate > 0
    ? Math.max(0, hours - Math.max(0, (.9 - state.fatigue) / fatigueRate))
    : Math.min(hours, Math.max(0, (state.fatigue - .9) / (-fatigueRate * recovery)));
  const health = clamp01(state.health + hours * .003 - starving * .018 - exhausted * .012);
  return {
    hunger, fatigue, health,
    hygiene: clamp01(state.hygiene - hours * dirtRate),
    ill: (state.ill && health < .85) || (health < .7 && (hunger >= .9 || fatigue >= .9)),
    injuries: state.injuries.flatMap(injury => {
      const remainingHours = injury.remainingHours - hours * (injury.treated ? 2 : 1);
      return remainingHours > 0 ? [{ ...injury, remainingHours }] : [];
    }),
  };
}

export function tick(state: Needs, hours: number, activity: Activity): Needs {
  return advance(state, hours, activity);
}

const meals = { diner: .37, grocery_cooked: .35, snack: .16, boarding_house_board: .34 } as const;
export type Meal = keyof typeof meals;

export function eat(state: Needs, meal: Meal): Needs {
  return { ...state, hunger: clamp01(state.hunger - meals[meal]) };
}

const beds = { bed: 1, bench: .48, cell: .65 } as const;
export type BedQuality = keyof typeof beds;

export function sleep(state: Needs, hours: number, bedQuality: BedQuality): Needs {
  return advance(state, hours, 'sleeping', beds[bedQuality]);
}

export function wash(state: Needs): Needs {
  return { ...state, hygiene: 1 };
}

export function injure(state: Needs, kind: Injury): Needs {
  const spec = injuries[kind];
  return {
    ...state, health: clamp01(state.health - spec.healthLoss),
    injuries: [...state.injuries.filter(injury => injury.kind !== kind),
      { kind, remainingHours: spec.hours, treated: false }],
  };
}

export function treat(state: Needs, kind: Injury): Needs {
  return { ...state, injuries: state.injuries.map(injury => injury.kind === kind ? { ...injury, treated: true } : injury) };
}

export function effects(state: Needs) {
  const labels: string[] = [];
  if (state.hunger >= .9) labels.push('starving');
  else if (state.hunger >= .48) labels.push('hungry');
  if (state.fatigue >= .85) labels.push('exhausted');
  else if (state.fatigue >= .55) labels.push('tired');
  if (state.hygiene < .4) labels.push('unwashed');
  if (state.ill) labels.push('ill');
  for (const injury of state.injuries) labels.push(injury.kind);

  const sprain = state.injuries.some(injury => injury.kind === 'sprain') ? .1 : 0;
  return {
    labels,
    walkingSpeedMultiplier: 1 - .13 * state.hunger - .18 * state.fatigue - .15 * (1 - state.health) - sprain,
    socialReactionModifier: -.25 * Math.max(0, .6 - state.hygiene) - .2 * Math.max(0, state.fatigue - .55),
    dozeChancePerHour: .8 * Math.max(0, state.fatigue - .85),
  };
}
