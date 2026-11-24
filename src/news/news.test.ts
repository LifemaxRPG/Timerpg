import { expect, it } from 'vitest';
import { dayNumberOf } from '../calendar';
import { edition, paperPrice, radioAt, tvNewsAvailable, type NewsEvent } from './index';

const town = { name: 'Capital' };
const year = 1952;
const day = dayNumberOf({ year, month: 9, day: 15 })!;
const crime: NewsEvent = {
  id: 'hardware', kind: 'crime', day: day - 1, reportedDay: day, subject: 'Thief Takes Tools from Elm St. Hardware',
  detail: 'Police took a report from the hardware store.', publicDescription: 'Man in Grey Coat',
};

it('prints the reported description on page three only on the report date', () => {
  expect(edition(town, day, [crime, crime], year, 'news')!.items.filter(item => item.id === crime.id)).toMatchObject([
    { headline: 'Thief Takes Tools from Elm St. Hardware; Police Seek Man in Grey Coat', page: 3 },
  ]);
  for (const issue of [
    edition(town, day, [{ ...crime, reportedDay: null }], year, 'news'),
    edition(town, day - 1, [crime], year, 'news'),
    edition(town, day + 1, [crime], year, 'news'),
  ]) expect(issue!.items.some(item => item.id === crime.id)).toBe(false);
});

it('has calendar notices, filler, era prices and deterministic JSON', () => {
  const paper = edition(town, day, [], year, 'news')!;
  expect(paper).toEqual(JSON.parse(JSON.stringify(edition(town, day, [], year, 'news'))));
  expect(paper.items).toEqual(expect.arrayContaining([
    expect.objectContaining({ headline: 'Lantern Festival' }),
    expect.objectContaining({ section: 'advertising' }),
    expect.objectContaining({ section: 'sport' }),
  ]));
  expect(paperPrice(1925)!).toBeLessThan(paperPrice(2025)!);
  expect(paper.priceCents).toBe(5);
});

it('gates radio and television and schedules distinct era radio programs', () => {
  expect(radioAt(1921, 7, 2, 'station')).toBeNull();
  expect(radioAt(1922, 7, 2, 'station')?.program).toBe('news');
  expect(radioAt(1952, 6.2, 2, 'station')?.program).toBe('farm_report');
  expect(radioAt(1952, 7.2, 2, 'station')?.genre).toMatch(/dance band|swing/);
  expect(radioAt(1952, 19.2, 2, 'station')?.program).toBe('serial');
  expect(radioAt(1952, 14.2, 6, 'station')?.program).toBe('ball_game');
  expect(radioAt(1952, 7.2, 2, 'station')).toEqual(radioAt(1952, 7.2, 2, 'station'));
  expect(tvNewsAvailable(1947)).toBe(false);
  expect(tvNewsAvailable(1948)).toBe(true);
});
