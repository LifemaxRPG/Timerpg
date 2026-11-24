import type { Outfit } from '../clothes';
import type { Weight } from '../economy';
import { FIRST_YEAR, LAST_YEAR } from '../economy';
import { ITEMS } from '../economy/catalogue';
import { rand } from '../sim/rng';

export type Item = Readonly<{ id: string; itemId: string }>;
export type ContainerKind = 'pocket' | 'coat' | 'bag' | 'briefcase' | 'hand'
  | 'glovebox' | 'trunk' | 'drawer' | 'home_hiding_place';
export type Container = Readonly<{
  id: string;
  kind: ContainerKind;
  maxSize: Weight;
  capacity: number;
  concealment: 'covered' | 'outline' | 'plain' | 'off_person';
}>;
export type Carried = Readonly<{ item: Item; container: Container }>;

export function outfitContainers(outfit: Outfit): Container[] {
  const workwear = outfit.garments.some(garment => garment.slot === 'workwear');
  const outerwear = outfit.garments.find(garment => garment.slot === 'outerwear');
  const pockets: Container[] = [
    { id: 'pocket', kind: 'pocket', maxSize: 'pocket', capacity: workwear ? 4 : 2, concealment: 'covered' },
  ];
  if (outerwear) {
    const coat = outerwear.id === 'brown_coat' || outerwear.id === 'gray_coat';
    pockets.push({ id: 'coat', kind: 'coat', maxSize: coat ? 'handheld' : 'pocket',
      capacity: coat ? 3 : 1, concealment: 'outline' });
  }
  return pockets;
}

const storage = {
  bag: ['handheld', 6, 'covered'],
  briefcase: ['handheld', 4, 'covered'],
  hand: ['heavy', 5, 'plain'],
  glovebox: ['handheld', 3, 'off_person'],
  trunk: ['heavy', 16, 'off_person'],
  drawer: ['handheld', 6, 'off_person'],
  home_hiding_place: ['bulky', 8, 'off_person'],
} as const satisfies Record<Exclude<ContainerKind, 'pocket' | 'coat'>,
  readonly [Weight, number, Container['concealment']]>;

export function storageContainer(kind: keyof typeof storage, id: string): Container {
  const [maxSize, capacity, concealment] = storage[kind];
  return { id, kind, maxSize, capacity, concealment };
}

const units: Record<Weight, number> = { pocket: 1, handheld: 2, bulky: 4, heavy: 5 };
const catalogue = new Map(ITEMS.map(item => [item.id, item.weight]));

export function place(item: Item, container: Container, carried: readonly Carried[] = []): Carried | null {
  const size = catalogue.get(item.itemId);
  if (!size || units[size] > units[container.maxSize]) return null;
  let occupied = 0;
  for (const entry of carried) {
    if (entry.item.id === item.id || entry.container.id !== container.id) continue;
    const otherSize = catalogue.get(entry.item.itemId);
    if (!otherSize) return null;
    occupied += units[otherSize];
  }
  return occupied + units[size] <= container.capacity ? { item, container } : null;
}

export function visibility(carried: readonly Carried[]): { bulge: boolean; visibleItems: Item[] } {
  return {
    bulge: carried.some(({ item, container }) =>
      container.concealment === 'outline' && catalogue.get(item.itemId) === 'handheld'),
    visibleItems: carried.filter(({ container }) => container.concealment === 'plain').map(({ item }) => item),
  };
}

export type SearchKind =
  | { kind: 'pat_down' }
  | { kind: 'bag'; containerId: string }
  | { kind: 'car'; containerIds: readonly string[]; authority: 'none' | 'cause' | 'warrant' }
  | { kind: 'room'; containerIds: readonly string[]; authority: 'none' | 'warrant' };

export function searchFinds(carried: readonly Carried[], searchKind: SearchKind, year: number, seed: string): Carried[] {
  if (!Number.isInteger(year) || year < FIRST_YEAR || year > LAST_YEAR) return [];
  return carried.filter(({ item, container: { id, kind } }) => {
    switch (searchKind.kind) {
      case 'pat_down':
        return kind === 'pocket' || kind === 'coat';
      case 'bag':
        return id === searchKind.containerId && (kind === 'bag' || kind === 'briefcase');
      case 'car':
        return searchKind.containerIds.includes(id) && (kind === 'glovebox' || kind === 'trunk')
          && (searchKind.authority === 'warrant' || (searchKind.authority === 'cause' && year >= 1970));
      case 'room':
        return searchKind.authority === 'warrant' && searchKind.containerIds.includes(id)
          && (kind === 'drawer' || (kind === 'home_hiding_place' && rand(seed, 'room_search', year, id, item.id) < .6));
    }
  });
}
