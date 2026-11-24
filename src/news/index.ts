import { dateOf, holidaysOn } from '../calendar';
import { formatMoney, priceIn, priceIndex } from '../economy';
import { rand } from '../sim/rng';

export type NewsTown = Readonly<{ name: string; paperName?: string }>;
type PublicEvent = Readonly<{ id: string; day: number; subject: string; detail: string; major?: boolean }>;

export type NewsEvent =
  | (PublicEvent & { kind: 'crime'; reportedDay: number | null; publicDescription?: string })
  | (PublicEvent & { kind: 'fire' | 'death' | 'opening' | 'closing' | 'weather' | 'prices' });

export type NewsItem = Readonly<{
  id: string; headline: string; body: string; page: number;
  size: 'lead' | 'column' | 'brief'; section: 'local' | 'notices' | 'sport' | 'advertising';
}>;

export function paperPrice(year: number): number | null {
  const index = priceIndex(year);
  return index === null ? null : Math.max(1, Math.round(5 * index / priceIndex(1952)!));
}

const headlineStarts = {
  fire: ['Fire Reported at', 'Fire Report:'], death: ['In Memoriam:', 'Obituary:'],
  opening: ['Doors Open at', 'Now Open:'], closing: ['Doors Close at', 'Closure:'],
  weather: ['The Weather:', 'Forecast:'], prices: ['Market Notes:', 'Price Watch:'],
} as const;

function article(event: NewsEvent, day: number, year: number): NewsItem | null {
  if (event.day > day) return null;
  if (event.kind === 'crime') {
    if (event.reportedDay === null || event.reportedDay !== day || event.reportedDay < event.day) return null;
    const description = event.publicDescription?.trim();
    const headline = description
      ? `${event.subject}; ${year < 1970 ? 'Police Seek' : 'Police Description:'} ${description}`
      : event.subject;
    return {
      id: event.id, headline,
      body: description ? `${event.detail} The published description is based on public witness reports.` : event.detail,
      page: event.major ? 1 : 3, size: event.major ? 'lead' : 'column', section: 'local',
    };
  }
  if (event.day !== day) return null;
  const notices = event.kind === 'weather' || event.kind === 'prices';
  const page = event.major ? 1 : notices ? 2 : 3;
  return {
    id: event.id, headline: `${headlineStarts[event.kind][year < 1970 ? 0 : 1]} ${event.subject}`,
    body: event.detail, page, size: event.major ? 'lead' : notices ? 'brief' : 'column',
    section: notices ? 'notices' : 'local',
  };
}

export function edition(town: NewsTown, day: number, events: readonly NewsEvent[], year: number, seed: string) {
  const date = dateOf(day);
  const priceCents = paperPrice(year);
  if (!date || date.year !== year || priceCents === null) return null;
  const localSeed = JSON.stringify([seed, town.name]);
  const articles = new Map<string, NewsItem>();
  for (const event of events) {
    const item = article(event, day, year);
    if (item && !articles.has(item.id)) articles.set(item.id, item);
  }
  const calendar: NewsItem[] = holidaysOn(date).map(holiday => ({
    id: 'holiday:' + holiday.name, headline: holiday.name,
    body: [holiday.closes.length ? `Closed today: ${holiday.closes.join(', ')}.` : '',
      holiday.opens.length ? `On today: ${holiday.opens.join(', ')}.` : ''].filter(Boolean).join(' '),
    page: 2, size: 'brief', section: 'notices',
  }));
  const homeScore = Math.floor(rand(localSeed, 'news_home', day) * 9);
  const awayScore = (homeScore + 1 + Math.floor(rand(localSeed, 'news_away', day) * 8)) % 9;
  const adPrice = priceIn('peaches', year);
  const filler: NewsItem[] = [
    { id: 'social', headline: year < 1970 ? 'Around Town' : 'Community Board',
      body: 'The paper welcomes notices from clubs and neighborhood groups.',
      page: 2, size: 'brief', section: 'notices' },
    { id: 'sport', headline: 'Recreation League Scoreboard',
      body: `Home nine ${homeScore}, visitors ${awayScore}.`, page: 4, size: 'brief', section: 'sport' },
  ];
  if (adPrice !== null) filler.push({
    id: 'advert', headline: 'From the General Store',
    body: `Canned peaches, ${formatMoney(adPrice, year)} a tin.`, page: 4, size: 'brief', section: 'advertising',
  });
  const items = [...articles.values(), ...calendar, ...filler].sort((a, b) => a.page - b.page);
  return { name: town.paperName ?? `${town.name} Courier`, day, year, priceCents, items };
}

export function tvNewsAvailable(year: number): boolean {
  return Number.isInteger(year) && year >= 1948 && year <= 2030;
}

const callSigns = ['VQRT', 'XHVL', 'QMVN', 'XVLP'] as const;
type Program = 'news' | 'farm_report' | 'serial' | 'ball_game' | 'music' | 'off_air';

export function radioAt(year: number, hour: number, weekday: number, seed: string) {
  if (!Number.isInteger(year) || year < 1922 || year > 2030 || !Number.isFinite(hour) || hour < 0 || hour >= 24
    || !Number.isInteger(weekday) || weekday < 0 || weekday > 6) return null;
  const station = callSigns[Math.floor(rand(seed, 'radio_station') * callSigns.length)]!;
  const clockHour = Math.floor(hour);
  let program: Program;
  if (year < 1970 && (clockHour < 6 || clockHour >= 23)) program = 'off_air';
  else if (hour - clockHour < 5 / 60) program = 'news';
  else if (clockHour === 6 && weekday >= 1 && weekday <= 6) program = 'farm_report';
  else if ((weekday === 0 || weekday === 6) && clockHour >= 13 && clockHour < 16) program = 'ball_game';
  else if (year <= 1962 && clockHour === 19) program = 'serial';
  else program = 'music';

  const genres = year < 1930 ? ['ragtime', 'jazz'] : year < 1960 ? ['dance band', 'swing']
    : year < 1980 ? ['soul', 'rock and roll'] : year < 2000 ? ['new wave', 'pop rock'] : ['indie rock', 'pop'];
  return {
    station, program, genre: program === 'music'
      ? genres[Math.floor(rand(seed, 'radio_genre', year, weekday, clockHour) * genres.length)]! : null,
  };
}
