import { dateOf, type Season } from '../calendar';
import { rand } from '../sim/rng';

const climates = {
  temperate_coast: { means: [5, 6, 8, 11, 15, 18, 21, 21, 18, 14, 9, 6], spread: 9,
    rain: [.27, .24, .16, .27], snow: .12, fog: .32, storm: 'autumn' },
  inland_plains: { means: [-5, -2, 4, 11, 17, 23, 27, 25, 19, 12, 4, -3], spread: 12,
    rain: [.15, .31, .24, .18], snow: .4, fog: .11, storm: 'spring' },
  mountain: { means: [-9, -7, -3, 2, 8, 13, 16, 15, 10, 4, -3, -8], spread: 11,
    rain: [.21, .31, .26, .24], snow: .65, fog: .2, storm: 'summer' },
  warm_south: { means: [14, 16, 19, 23, 26, 29, 31, 31, 28, 24, 19, 15], spread: 8,
    rain: [.19, .21, .4, .24], snow: 0, fog: .18, storm: 'summer' },
} as const;
const seasonIndex: Record<Season, number> = { winter: 0, spring: 1, summer: 2, autumn: 3 };

export type Climate = keyof typeof climates;
export type Sky = 'clear' | 'cloudy' | 'overcast' | 'rain' | 'storm' | 'snow' | 'fog';
export type Weather = Readonly<{ sky: Sky; temperatureC: number; windKph: number; visibilityM: number }>;

export function temperatureRange(climate: Climate, month: number): { minC: number; maxC: number } | null {
  if (!Object.prototype.hasOwnProperty.call(climates, climate) || !Number.isInteger(month) || month < 1 || month > 12) return null;
  const { means, spread } = climates[climate];
  return { minC: means[month - 1]! - spread, maxC: means[month - 1]! + spread };
}
