import { dateOf, dayNumberOf, type CalendarDate, type Season } from '../calendar';
import { rand } from '../sim/rng';

const climates = {
  temperate_coast: { means: [5, 6, 8, 11, 15, 18, 21, 21, 18, 14, 9, 6], spread: 9,
    rain: [.27, .24, .16, .27], clear: [.22, .33, .52, .26], cloudy: .31, snow: .12, fog: .32, storm: 'autumn' },
  inland_plains: { means: [-5, -2, 4, 11, 17, 23, 27, 25, 19, 12, 4, -3], spread: 12,
    rain: [.15, .31, .24, .18], clear: [.46, .49, .63, .55], cloudy: .27, snow: .4, fog: .11, storm: 'spring' },
  mountain: { means: [-9, -7, -3, 2, 8, 13, 16, 15, 10, 4, -3, -8], spread: 11,
    rain: [.21, .31, .26, .24], clear: [.35, .43, .54, .46], cloudy: .28, snow: .65, fog: .2, storm: 'summer' },
  warm_south: { means: [14, 16, 19, 23, 26, 29, 31, 31, 28, 24, 19, 15], spread: 8,
    rain: [.19, .21, .4, .24], clear: [.74, .78, .82, .79], cloudy: .13, snow: 0, fog: .18, storm: 'summer' },
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

const visibility: Record<Sky, number> = {
  clear: 20000, cloudy: 15000, overcast: 10000, rain: 2500, storm: 700, snow: 450, fog: 75,
};

export function weatherAt(climate: Climate, dayNumber: number, hour: number, seed: string, startDate?: CalendarDate): Weather | null {
  if (!Number.isFinite(hour) || hour < 0 || hour >= 24) return null;
  const date = dateOf(dayNumber, startDate);
  if (!date || !temperatureRange(climate, date.month)) return null;

  const profile = climates[climate];
  const absoluteDay = dayNumberOf(date)!;
  const totalHour = absoluteDay * 24 + hour;
  const block = Math.floor(totalHour / 48);
  const split = 18 + Math.floor(rand(seed, 'weather_split', climate, block) * 13);
  const segment = totalHour - block * 48 < split ? 0 : 1;
  const frontDay = block * 2 + (segment === 1 && split >= 24 ? 1 : 0);
  const front = dateOf(frontDay)!;
  const roll = rand(seed, 'weather_front', climate, block, segment);
  const mean = profile.means[date.month - 1]!;
  const variation = 2 * rand(seed, 'weather_temperature', climate, block, segment) - 1;
  const temperatureC = Math.round(mean + profile.spread * (
    .5 * variation + .5 * Math.cos((hour - 15) * Math.PI / 12)
  ));

  const coldMonth = mean <= 6 && (date.season === 'winter' || date.month === 11 || date.month === 3);
  const snowChance = coldMonth ? profile.snow : 0;
  const rainChance = profile.rain[seasonIndex[front.season]] * (1 - snowChance);
  let sky: Sky;
  if (roll < snowChance) sky = temperatureC <= 1 ? 'snow' : 'rain';
  else if (roll < snowChance + rainChance) {
    sky = front.season === profile.storm && roll < snowChance + rainChance * .3 ? 'storm' : 'rain';
  } else {
    const dryRoll = (roll - snowChance - rainChance) / (1 - snowChance - rainChance);
    const clear = profile.clear[seasonIndex[front.season]];
    sky = dryRoll < clear ? 'clear' : dryRoll < clear + profile.cloudy ? 'cloudy' : 'overcast';
  }

  const fogDay = hour < 7 ? absoluteDay - 1 : absoluteDay;
  const fogSeason = date.season === 'autumn' ? 1 : .35;
  if ((hour >= 18 || hour < 7) && sky !== 'snow' && sky !== 'storm' && sky !== 'rain'
    && rand(seed, 'weather_fog', climate, fogDay) < profile.fog * fogSeason) sky = 'fog';

  const windRoll = rand(seed, 'weather_wind', climate, block, segment);
  const windKph = sky === 'storm' ? Math.round(35 + windRoll * 40)
    : sky === 'fog' ? Math.round(2 + windRoll * 6) : Math.round(5 + windRoll * 20);
  return { sky, temperatureC: temperatureC + 0, windKph, visibilityM: visibility[sky] };
}

const effectsBySky: Record<Sky, readonly [faceM: number, walk: number, crowd: number, grip: number]> = {
  clear: [35, 1, 1, 1], cloudy: [32, 1, .98, 1], overcast: [29, 1, .94, 1],
  rain: [18, .94, .73, .81], storm: [10, .83, .40, .65], snow: [12, .79, .59, .53],
  fog: [6, .96, .79, .9],
};

export function weatherEffects(weather: Weather) {
  const [face, walk, crowd, grip] = effectsBySky[weather.sky];
  const heat = Math.min(1, Math.max(0, (weather.temperatureC - 30) / 10));
  return {
    faceRecognitionM: Math.min(face, weather.visibilityM),
    walkingSpeedMultiplier: walk,
    streetActivityMultiplier: Math.round(crowd * (1 - .4 * heat) * 100) / 100,
    carGripMultiplier: grip,
  };
}
