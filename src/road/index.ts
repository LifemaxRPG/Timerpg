import { priceIndex } from '../economy';
import { tick, type Needs } from '../needs';
import { weatherEffects, type Weather } from '../weather';
import { rand } from '../sim/rng';

export type Road = Readonly<{
  id: string; lengthMiles: number; kind: 'county' | 'highway' | 'expressway';
  from: string; to: string; terrain: 'open' | 'farmland' | 'hills' | 'mountains'; remote?: boolean;
}>;
export type RoadsideKind = 'diner' | 'motel' | 'gas_station' | 'fruit_stand'
  | 'tourist_trap' | 'rest_area' | 'truck_stop';

const spacing = { county: 9, highway: 14, expressway: 24 };
const validRoad = (road: Road) => Number.isFinite(road.lengthMiles) && road.lengthMiles > 0;

export function roadsideAlong(road: Road, year: number, seed: string) {
  if (!validRoad(road) || priceIndex(year) === null) return [];
  const stride = spacing[road.kind] * (road.remote ? 1.9 : 1) * (year < 1940 ? 1.35 : year >= 1980 ? .9 : 1);
  const kinds: RoadsideKind[] = road.kind === 'expressway' ? ['gas_station', 'truck_stop', 'diner']
    : ['diner', 'gas_station', 'tourist_trap'];
  if (year >= 1940) kinds.push('motel');
  if (road.terrain === 'farmland' && road.kind !== 'expressway') kinds.push('fruit_stand');
  if (road.kind === 'expressway' && year >= 1956) kinds.push('rest_area');
  const places = [];
  for (let slot = 0; (slot + 1) * stride < road.lengthMiles; slot++) {
    const mile = (slot + .5 + .4 * rand(seed, 'roadside_mile', road.id, slot)) * stride;
    const kind = kinds[Math.floor(rand(seed, 'roadside_kind', road.id, slot) * kinds.length)]!;
    places.push({ id: `${road.id}:stop:${slot}`, kind, mile: Math.round(mile * 10) / 10 });
  }
  return places;
}
