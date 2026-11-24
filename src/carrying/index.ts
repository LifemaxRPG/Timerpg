import type { Outfit } from '../clothes';
import type { Weight } from '../economy';

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
