import { describe, expect, it } from 'vitest';
import { dayNumberOf } from '../calendar';
import { priceIndex } from '../economy';
import { ballGame, cinemaProgramme, crowdAt, musicNight, seasonFixtures } from './index';

const seed = 'strand-test';
const ordinal = (year: number, month: number, day: number) =>
  dayNumberOf({ year, month, day })! - dayNumberOf({ year, month: 1, day: 1 })!;

describe('entertainment', () => {
  it('schedules silent programmes until talkies arrive in 1928', () => {
    expect(cinemaProgramme('cinema', 1927, 180, seed)?.format).toBe('silent');
    expect(cinemaProgramme('cinema', 1928, 180, seed)?.format).toBe('sound');
  });

  it('opens the drive-in from summer 1933 on weekend evenings', () => {
    expect(cinemaProgramme('drive_in', 1932, ordinal(1932, 7, 8), seed)).toBeNull();
    const friday = ordinal(1933, 7, 7);
    expect(cinemaProgramme('drive_in', 1933, friday, seed)?.shows[0]?.hour).toBe(20.5);
    expect(cinemaProgramme('drive_in', 1933, friday - 1, seed)).toBeNull();
    expect(cinemaProgramme('drive_in', 1933, ordinal(1933, 1, 6), seed)).toBeNull();
  });

  it('schedules the 1952 Friday double feature and dance band and indexes later prices', () => {
    const friday = ordinal(1952, 7, 4);
    const programme = cinemaProgramme('cinema', 1952, friday, seed)!;
    expect(programme.priceCents).toBe(50);
    expect(programme.shows.map(show => show.hour)).toEqual([19, 21]);
    expect(programme.shows[0]?.title).not.toBe(programme.shows[1]?.title);
    expect(musicNight('dance_hall', 1952, friday)).toMatchObject({ hour: 20, priceCents: 75 });
    expect(musicNight('club', 1952, friday + 1)).toBeNull();
    expect(cinemaProgramme('cinema', 2000, friday, seed)?.priceCents)
      .toBe(Math.round(50 * priceIndex(2000)! / priceIndex(1952)!));
  });

  it('draws Main Street foot traffic to the park before a home game only', () => {
    const fixtures = seasonFixtures(1952, seed);
    const home = fixtures.find(fixture => fixture.home)!.day;
    const away = fixtures.find(fixture => !fixture.home)!.day;
    expect(crowdAt('park_street', 1952, home, 13.5, seed)).toBeGreaterThan(crowdAt('park_street', 1952, away, 13.5, seed));
    expect(crowdAt('main_street', 1952, home, 13.5, seed)).toBeLessThan(crowdAt('main_street', 1952, away, 13.5, seed));
    expect(crowdAt('ballpark', 1952, home, 14, seed)).toBe(72);
    expect(crowdAt('ballpark', 1952, away, 14, seed)).toBe(0);
    expect(crowdAt('park_street', 1952, home, 17, seed)).toBe(78);
  });

  it('returns repeatable schedules and final scores with no off-season fixtures', () => {
    const fixtures = seasonFixtures(1952, seed);
    expect(fixtures).toEqual(seasonFixtures(1952, seed));
    expect(fixtures.length).toBeGreaterThan(20);
    expect(fixtures[1]!.day - fixtures[0]!.day).toBe(7);
    for (const fixture of fixtures) {
      expect(ballGame(1952, fixture.day, seed)).toEqual(fixture);
      expect(fixture.result.homeRuns).not.toBe(fixture.result.awayRuns);
    }
    expect(ballGame(1952, ordinal(1952, 1, 5), seed)).toBeNull();
    expect(cinemaProgramme('cinema', 1952, 360, seed)).toEqual(cinemaProgramme('cinema', 1952, 360, seed));
    expect(crowdAt('main_street', 1952, 366, 12, seed)).toBe(0);
  });
});
