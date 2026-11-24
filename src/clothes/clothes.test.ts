import { expect, it } from 'vitest';
import { appearanceOf, matchScore, purchaseRecord, wardrobeOf } from './index';

const wardrobe = wardrobeOf(1952, 'clothing');
const item = (id: string) => wardrobe.find(row => row.id === id)!;
const base = { height: 'tall', build: 'average', hair: 'dark', clothingColor: 'gray' } as const;

it('changes the visible coat and lowers the old description match', () => {
  const dressed = appearanceOf(base, { garments: [item('brown_coat')] });
  expect(dressed.appearance.clothingColor).toBe('brown');
  expect(matchScore({ height: 'tall', clothingColor: 'gray' }, dressed.visible))
    .toBeLessThan(matchScore({ height: 'tall', clothingColor: 'gray' }, base));
});

it('keeps the underlying hair type while a hat hides it from observation', () => {
  const dressed = appearanceOf(base, {
    garments: [item('felt_hat'), item('round_glasses'), item('wool_scarf')], dyedHair: 'brown',
  });
  expect(dressed.appearance.hair).toBe('brown');
  expect(dressed.visible.hair).toBeUndefined();
  expect(dressed.faceRecognizability).toBeCloseTo(.52);
  expect(matchScore({ hair: 'dark' }, dressed.visible)).toBe(.5);
});

it('gates wardrobe items by era and outlet and scales prices by era', () => {
  expect(wardrobe.some(row => row.id === 'windbreaker')).toBe(false);
  expect(wardrobeOf(1980).some(row => row.id === 'windbreaker')).toBe(true);
  expect(wardrobeOf(1900, 'pharmacy').some(row => row.id === 'brown_dye')).toBe(false);
  expect(wardrobeOf(1952, 'pharmacy').some(row => row.id === 'brown_dye')).toBe(true);
  expect(item('brown_coat').priceCents + item('felt_hat').priceCents).toBe(1400);
  expect(wardrobeOf(2025).find(row => row.id === 'brown_coat')!.priceCents).toBeGreaterThan(item('brown_coat').priceCents);
  expect(wardrobe).toEqual(JSON.parse(JSON.stringify(wardrobeOf(1952, 'clothing'))));
});

it('records supplied recollections without inventing payment or demeanor', () => {
  const record = purchaseRecord(item('brown_coat'), 21, 'main', { paidCash: true, nervous: true, publicCrimeDay: 20 });
  expect(record).toMatchObject({ color: 'brown', shortlyAfterPublicCrime: true, paidCash: true, nervous: true });
  expect(purchaseRecord(item('brown_coat'), 21, 'main')).toMatchObject({
    shortlyAfterPublicCrime: null, paidCash: null, nervous: null,
  });
});
