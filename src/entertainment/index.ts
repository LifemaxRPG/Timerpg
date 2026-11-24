import { dateOf, dayNumberOf } from '../calendar';
import { priceIndex } from '../economy';
import { rand } from '../sim/rng';

function dateInYear(year: number, day: number) {
  const date = dateOf(day, { year, month: 1, day: 1 });
  return date?.year === year ? date : null;
}

function ticketPrice(priceIn1952Cents: number, year: number): number | null {
  const index = priceIndex(year);
  return index === null ? null : Math.round(priceIn1952Cents * index / priceIndex(1952)!);
}

type Film = { title: string; genre: string };
const silent: readonly Film[] = [
  { title: 'The Errant Hat', genre: 'silent comedy' },
  { title: 'Beyond the Last Trestle', genre: 'silent adventure' },
];
const talkies: readonly Film[] = [
  { title: 'The Bell at Low Water', genre: 'mystery' },
  { title: 'Two Seats to Briar Creek', genre: 'romantic comedy' },
  { title: 'Last Freight at Sundown', genre: 'western' },
  { title: 'A Mile of Moonlight', genre: 'musical' },
];
const modern: readonly Film[] = [
  { title: 'The Orchard Frequency', genre: 'science fiction' },
  { title: 'One Lantern Left', genre: 'family adventure' },
  { title: 'Borrowed Weather', genre: 'comedy' },
];

export type CinemaKind = 'cinema' | 'drive_in';

export function cinemaProgramme(kind: CinemaKind, year: number, day: number, seed: string) {
  const date = dateInYear(year, day);
  const priceCents = ticketPrice(kind === 'cinema' ? 50 : 65, year);
  if (!date || priceCents === null || year < (kind === 'cinema' ? 1905 : 1933)) return null;
  const weekend = date.weekday === 5 || date.weekday === 6;
  if (kind === 'drive_in' && year === 1933 && date.month < 6) return null;
  if (kind === 'drive_in' && (!weekend || date.season === 'winter')) return null;
  const films = year < 1928 ? silent : year < 1970 ? talkies : modern;
  const first = Math.floor(rand(seed, 'cinema', kind, year, date.weekOfYear) * films.length);
  const doubleFeature = weekend && year >= 1933 && (year <= 1969 || kind === 'drive_in');
  const showtimes = kind === 'drive_in' ? (doubleFeature ? [20.5, 22.5] : [20.5])
    : doubleFeature ? [19, 21] : date.weekday === 0 ? [15, 19] : [19];
  return {
    format: year < 1928 ? 'silent' : 'sound',
    priceCents, doubleFeature,
    shows: showtimes.map((hour, index) => ({
      hour, durationHours: year < 1928 ? 1 : 2,
      ...films[(first + (doubleFeature ? index : 0)) % films.length]!,
    })),
  };
}

export type MusicKind = 'dance_hall' | 'club' | 'music_bar';

export function musicNight(kind: MusicKind, year: number, day: number) {
  const date = dateInYear(year, day);
  const priceCents = ticketPrice(kind === 'dance_hall' ? 75 : kind === 'club' ? 125 : 25, year);
  if (!date || priceCents === null) return null;
  const friday = date.weekday === 5;
  const saturday = date.weekday === 6;
  if (kind === 'dance_hall' && year >= 1900 && (friday || saturday)) {
    return { hour: 20, endHour: 23, priceCents, music: year < 1960 ? 'live dance band' : 'live country band' };
  }
  if (kind === 'club' && year >= 1960 && saturday) {
    return { hour: 21, endHour: 24, priceCents, music: year < 1980 ? 'live soul band' : 'DJ' };
  }
  if (kind === 'music_bar' && year >= 1934 && (friday || saturday)) {
    return { hour: 19, endHour: 22, priceCents, music: 'acoustic trio' };
  }
  return null;
}

export function ballGame(year: number, day: number, seed: string) {
  const date = dateInYear(year, day);
  if (!date || priceIndex(year) === null || date.weekday !== 6 || date.month < 4 || date.month > 9) return null;
  const monthStart = dayNumberOf({ year, month: date.month, day: 1 })!;
  const yearStart = dayNumberOf({ year, month: 1, day: 1 })!;
  const home = Math.floor((monthStart - yearStart + date.day - 1) / 7) % 2 === 0;
  const score = (side: string) => Math.floor(rand(seed, 'baseball', year, day, side) * 9);
  const townRuns = score('town');
  const opponentRuns = score('opponent');
  const tieBreakTown = rand(seed, 'baseball', year, day, 'extra_inning') < .5;
  const finalTown = townRuns + Number(townRuns === opponentRuns && tieBreakTown);
  const finalOpponent = opponentRuns + Number(townRuns === opponentRuns && !tieBreakTown);
  return {
    day, hour: 14, endHour: 17, home,
    opponent: ['Briar Creek', 'Alder Ford', 'Rook Hollow'][Math.floor(rand(seed, 'opponent', year, day) * 3)]!,
    result: { homeRuns: home ? finalTown : finalOpponent, awayRuns: home ? finalOpponent : finalTown },
  };
}

export function seasonFixtures(year: number, seed: string) {
  const start = dayNumberOf({ year, month: 1, day: 1 });
  const april = dayNumberOf({ year, month: 4, day: 1 });
  const october = dayNumberOf({ year, month: 10, day: 1 });
  if (start === null || april === null || october === null || priceIndex(year) === null) return [];
  const firstSaturday = april + (6 - dateOf(april)!.weekday + 7) % 7;
  const fixtures = [];
  for (let date = firstSaturday; date < october; date += 7) {
    const fixture = ballGame(year, date - start, seed);
    if (fixture) fixtures.push(fixture);
  }
  return fixtures;
}

export type CrowdPlace = CinemaKind | MusicKind | 'ballpark' | 'park_street' | 'main_street';

export function crowdAt(place: CrowdPlace, year: number, day: number, hour: number, seed: string): number {
  if (!dateInYear(year, day) || priceIndex(year) === null || !Number.isFinite(hour) || hour < 0 || hour >= 24) return 0;
  const audiences = {
    cinema: cinemaProgramme('cinema', year, day, seed)?.shows.some(
      show => hour >= show.hour && hour < show.hour + show.durationHours) ? 24 : 0,
    drive_in: cinemaProgramme('drive_in', year, day, seed)?.shows.some(
      show => hour >= show.hour && hour < show.hour + show.durationHours) ? 18 : 0,
    dance_hall: 0, club: 0, music_bar: 0,
  };
  for (const kind of ['dance_hall', 'club', 'music_bar'] as const) {
    const event = musicNight(kind, year, day);
    if (event && hour >= event.hour && hour < event.endHour) {
      audiences[kind] = kind === 'dance_hall' ? 26 : kind === 'club' ? 19 : 12;
    }
  }
  const fixture = ballGame(year, day, seed);
  const atPark = fixture?.home && hour >= fixture.hour && hour < fixture.endHour ? 72 : 0;
  const parkTraffic = fixture?.home && (
    (hour >= fixture.hour - .5 && hour < fixture.hour) ||
    (hour >= fixture.endHour && hour < fixture.endHour + .5)) ? 72 : 0;
  const mainBaseline = hour >= 9 && hour < 22 ? 110 : 30;
  if (place === 'ballpark') return atPark;
  if (place === 'park_street') return 6 + parkTraffic;
  if (place === 'main_street') return Math.max(0, mainBaseline - atPark - parkTraffic
    - Object.values(audiences).reduce((total, count) => total + count, 0));
  return audiences[place] ?? 0;
}
