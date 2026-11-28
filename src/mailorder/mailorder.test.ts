import { expect, it } from 'vitest';
import { catalogue, placeOrder, type Address } from './index';

const address: Address = {
  label: 'General Delivery', pickup: 'post_office',
  destination: { id: 'post', town: 'Vale', continent: 'west', xKm: 0, yKm: 0 },
  merchant: { id: 'warehouse', town: 'Ford', continent: 'west', xKm: 800, yKm: 0 },
};
const buyer = { givenName: 'A. Rook', payment: 'money_order' } as const;
const tools = [{ itemId: 'hammer', quantity: 2 }];

it('takes three weeks by mail in 1925 and a few days online', () => {
  expect(placeOrder(tools, buyer, address, 1925, 100, 'mail')?.arrivesOn).toBe(121);
  expect(placeOrder(tools, { ...buyer, payment: 'card' }, address, 2000, 100, 'online')?.arrivesOn).toBe(102);
});

it('rejects era-restricted goods and requires station pickup for a house kit', () => {
  expect(catalogue(1925)).toContainEqual({ id: 'revolver', label: 'Revolver', cents: 1450 });
  expect(placeOrder([{ itemId: 'revolver', quantity: 1 }], buyer, address, 1968, 0, 'mail')).toBeNull();
  expect(placeOrder([{ itemId: 'liquor', quantity: 1 }], buyer, address, 1925, 0, 'mail')).toBeNull();
  expect(placeOrder([{ itemId: 'house_kit', quantity: 1 }], buyer, address, 1925, 0, 'mail')).toBeNull();
  expect(placeOrder([{ itemId: 'house_kit', quantity: 1 }], buyer, { ...address, pickup: 'station' }, 1925, 0, 'mail')).toBeTruthy();
});

it('keeps the name given, pickup address and payment on the order record', () => {
  expect(placeOrder(tools, buyer, address, 1925, 0, 'mail')?.record).toMatchObject({
    givenName: buyer.givenName, address: address.label, payment: buyer.payment,
  });
});

it('gates online and card ordering by era', () => {
  const cardBuyer = { ...buyer, payment: 'card' } as const;
  expect(placeOrder(tools, cardBuyer, address, 1994, 0, 'online')).toBeNull();
  expect(placeOrder(tools, cardBuyer, address, 1995, 0, 'online')).toBeTruthy();
  expect(placeOrder(tools, cardBuyer, address, 1959, 0, 'phone')).toBeNull();
});

it('replays results and refuses an invalid quantity', () => {
  const order = () => placeOrder(tools, buyer, address, 1925, 0, 'mail');
  expect(order()).toEqual(order());
  expect(order()?.totalCents).toBe(2 * order()!.lines[0]!.unitCents);
  expect(placeOrder([{ ...tools[0]!, quantity: -1 }], buyer, address, 1925, 0, 'mail')).toBeNull();
});
