import { expect, it } from 'vitest';
import { call, collectionHours, phoneShares, sendLetter, sendTelegram } from './index';

const home = { id: 'general', town: 'capital', continent: 'west', xKm: 0, yKm: 0 };
const neighbour = { ...home, id: 'house', xKm: 1 };
const far = { id: 'station', town: 'fartharbor', continent: 'east', xKm: 4000, yKm: 0 };

it('gates air mail and lands the 1925 overseas letter in nine days', () => {
  expect(sendLetter(home, far, 1925, 10)).toMatchObject({ deliveryDay: 19, transport: 'ship' });
  expect(sendLetter(home, far, 1925, 10, { service: 'air' })).toBeNull();
  const air = sendLetter(home, far, 1952, 10, { service: 'air' })!;
  expect(air.deliveryDay).toBeLessThan(sendLetter(home, far, 1952, 10)!.deliveryDay);
  expect(sendLetter(home, neighbour, 1917, 10, { service: 'air' })).toBeNull();
  expect(sendLetter(home, neighbour, 1925, 10, { hour: 17, grams: 56 })).toMatchObject({
    collectedDay: 11, deliveryDay: 12, costCents: 6,
  });
  expect(collectionHours(1925)).toEqual([9, 16]);
});

it('charges per telegram word and returns a same-day office record', () => {
  const message = { senderId: 'p', recipientId: 'prof', text: 'One two three four five six seven eight nine ten' };
  const telegram = sendTelegram(home, far, 1925, 10, message)!;
  expect(telegram).toMatchObject({ words: 10, costCents: 75, deliveryDay: 10,
    officeRecord: { ...message, officeId: home.id, destinationId: far.id, sentDay: 10 } });
  expect(sendTelegram(home, far, 1925, 10, { ...message, text: 'One two' })?.costCents).toBe(15);
  expect(sendTelegram(home, far, 1925, 10, message)).toEqual(JSON.parse(JSON.stringify(telegram)));
});

it('models adoption, operator hours, party lines and pricier long distance', () => {
  const from = { place: home, hasPhone: true, partyLine: true };
  const to = { place: neighbour, hasPhone: true };
  expect(call(from, to, 1925, 12)).toMatchObject({ connects: true, costCents: 5, eavesdropChance: .25 });
  expect(call(from, to, 1925, 23)).toMatchObject({ connects: false, costCents: 0, eavesdropChance: 0 });
  expect(call(from, to, 1890, 12)?.eavesdropChance).toBeLessThan(.25);
  expect(call(from, to, 1990, 12)?.eavesdropChance).toBe(0);
  expect(call(from, { place: far, hasPhone: true }, 1952, 12)!.costCents)
    .toBeGreaterThan(call(from, to, 1952, 12)!.costCents);
  expect(phoneShares(1952)!.home.working).toBeGreaterThan(phoneShares(1925)!.home.working);
});
