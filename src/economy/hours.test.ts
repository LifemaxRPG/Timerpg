import { expect, it } from 'vitest';
import { hoursOf, isOpen, staffed } from './index';

it('models Sunday closures, Saturday half-days, and later all-night fuel', () => {
  expect(hoursOf('general store', 1925, 0, 'main')).toEqual([]);
  expect(hoursOf('general', 1925, 6, 'main')).toEqual([{ open: 8, close: 13 }]);
  expect(isOpen('general', 1925, 6, 13, 'main')).toBe(false);
  expect(isOpen('gas station', 1952, 1, 3, 'main')).toBe(false);
  expect(isOpen('gas station', 1985, 1, 3, 'main')).toBe(true);
  expect(isOpen('diner', 1925, 0, 6, 'main')).toBe(true);
});

it('keeps outlet variation deterministic and extends Friday trade', () => {
  const friday = hoursOf('grocer', 1985, 5, 'main');
  expect(hoursOf('market', 1985, 5, 'main')).toEqual(friday);
  expect(friday[0]!.close).toBe(hoursOf('market', 1985, 4, 'main')[0]!.close + 1);
  const closing = Array.from({ length: 20 }, (_, i) => hoursOf('market', 1985, 5, String(i))[0]!.close);
  expect(new Set(closing).size).toBe(2);
  expect(hoursOf('missing', 1985, 5, 'main')).toEqual([]);
  expect(hoursOf('market', 1985, 7, 'main')).toEqual([]);
});

it('applies era-specific holiday closures and keeps the night clerk on duty', () => {
  const christmas = { month: 12, day: 25 };
  expect(isOpen('general', 1985, 2, 12, 'main', christmas)).toBe(false);
  expect(staffed('general', 1985, 2, 12, 'main', christmas)).toBe(0);
  expect(staffed('gas_station', 1985, 2, 3, 'main', christmas)).toBe(1);
  const julyFourth = { month: 7, day: 4 };
  expect(isOpen('general', 1952, 2, 12, 'main', julyFourth)).toBe(false);
  expect(isOpen('general', 1985, 2, 12, 'main', julyFourth)).toBe(true);
});

it('staffs the opening hour lightly and peaks at lunch', () => {
  expect(staffed('diner', 1952, 2, 5.5)).toBe(0);
  expect(staffed('diner', 1952, 2, 6)).toBe(1);
  expect(staffed('diner', 1952, 2, 12)).toBe(4);
  expect(staffed('diner', 1952, 2, 15)).toBe(2);
  expect(staffed('diner', 1952, 2, 21)).toBe(0);
});
