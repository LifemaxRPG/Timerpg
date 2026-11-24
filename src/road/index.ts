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

export type RoadNews = Readonly<{
  id: string; kind: 'escape_notice'; roadId: string; lastSeenCoat: string; headline: string;
}>;
type Encounter =
  | { kind: 'hitchhiker'; mile: number; destination: string; coat: string; backstory: string; newsId: string | null }
  | { kind: 'stranded_driver' | 'speed_trap' | 'road_work' | 'livestock' | 'fog_bank'; mile: number; detail: string };

export function encountersOn(
  road: Road, year: number, hour: number, weather: Weather, seed: string, news: readonly RoadNews[] = [],
): Encounter[] {
  if (!validRoad(road) || priceIndex(year) === null || !Number.isFinite(hour) || hour < 0 || hour >= 24) return [];
  const slot = Math.floor(hour / 4);
  const roll = (key: string) => rand(seed, 'road_encounter', road.id, year, slot, key);
  const mile = (key: string) => road.lengthMiles * (.1 + .8 * roll(key + '_marker'));
  const encounters: Encounter[] = [];
  const daytime = hour >= 6 && hour < 22;
  const hitchChance = year < 1930 ? .12 : year < 1980 ? .35 : .055;
  if (road.kind !== 'expressway' && daytime && roll('hitchhiker') < hitchChance) {
    const notice = news.filter(item => item.roadId === road.id)
      .reduce<RoadNews | null>((first, item) => !first || item.id < first.id ? item : first, null);
    const linked = notice && roll('news_link') < .35 ? notice : null;
    encounters.push({
      kind: 'hitchhiker', mile: mile('hitchhiker'), destination: road.to,
      coat: linked?.lastSeenCoat ?? (year < 1970 ? 'pea coat' : 'canvas jacket'),
      backstory: linked ? 'Says there is work waiting at the next town.' : 'Missed a lift to a cousin’s place.',
      newsId: linked?.id ?? null,
    });
  }
  if (year >= 1910 && roll('stranded_driver') < .13) encounters.push({
    kind: 'stranded_driver', mile: mile('stranded_driver'), detail: 'Driver waiting beside a car with a flat tyre.',
  });
  if (year >= 1920 && roll('speed_trap') < (year < 1955 ? .07 : .17)) encounters.push({
    kind: 'speed_trap', mile: mile('speed_trap'),
    detail: year < 1955 ? 'An officer times passing cars.' : 'A patrol car is monitoring traffic speed.',
  });
  if (daytime && roll('road_work') < .12) encounters.push({
    kind: 'road_work', mile: mile('road_work'), detail: 'A crew has closed one lane for repairs.',
  });
  if (road.kind === 'county' && road.terrain === 'farmland' && roll('livestock') < .17) encounters.push({
    kind: 'livestock', mile: mile('livestock'), detail: 'Cattle are crossing the road.',
  });
  if (weather.sky === 'fog') encounters.push({
    kind: 'fog_bank', mile: mile('fog_bank'), detail: `Visibility falls to ${weather.visibilityM} metres.`,
  });
  return encounters.sort((a, b) => a.mile - b.mile);
}

export type SpeedLimits = Readonly<Record<Road['kind'], number>>;

export function driveTime(road: Road, speedLimitByEra: SpeedLimits, weather: Weather): number | null {
  const limit = speedLimitByEra[road.kind];
  if (!validRoad(road) || !Number.isFinite(limit) || limit <= 0) return null;
  const terrain = road.terrain === 'mountains' ? .73 : road.terrain === 'hills' ? .88 : 1;
  const visibility = weather.sky === 'fog' ? .55 : 1;
  return road.lengthMiles / (limit * terrain * visibility * weatherEffects(weather).carGripMultiplier);
}

export function driverStops(needs: Needs, hours: number) {
  if (!Number.isFinite(hours) || hours < 0) return [];
  const requests = [{ kind: 'eat', need: 'hunger' }, { kind: 'sleep', need: 'fatigue' }] as const;
  return requests.flatMap(({ kind, need }) => {
    const threshold = .7;
    if (needs[need] >= threshold) return [{ kind, afterHours: 0 }];
    if (tick(needs, hours, 'idle')[need] < threshold) return [];
    let low = 0;
    let high = hours;
    for (let i = 0; i < 24; i++) {
      const mid = (low + high) / 2;
      if (tick(needs, mid, 'idle')[need] >= threshold) high = mid;
      else low = mid;
    }
    return [{ kind, afterHours: high }];
  }).sort((a, b) => a.afterHours - b.afterHours);
}
