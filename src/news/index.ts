import { dayNumberOf, dateOf, holidaysOn } from '../calendar';
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
