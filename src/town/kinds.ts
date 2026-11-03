export interface Rect { x: number; y: number; w: number; h: number }
export interface Road extends Rect { main: boolean; horizontal: boolean }

export type LotKind = 'estate' | 'villa' | 'house' | 'medium' | 'small' | 'apartment' | 'shop';
/** Plot sizes in tiles; they come from the sprite sizes (32px tiles). */
export const SIZES: Record<LotKind, { w: number; h: number }> = {
  // Houses get a one-tile margin on each side and a front yard (see FRONT): the sprite is
  // (w-2) x (h-FRONT) tiles, e.g. a 6x6-tile mobile home sits in an 8x8 plot.
  estate: { w: 25, h: 18 },
  villa: { w: 11, h: 15 },
  house: { w: 14, h: 17 },
  apartment: { w: 7, h: 13 },
  medium: { w: 10, h: 10 },
  small: { w: 8, h: 8 },
  shop: { w: 7, h: 13 },
};

/** Tiles of front yard at the bottom of a plot (the building stands above it). Apartments and shops have none. */
export const FRONT: Record<LotKind, number> = { estate: 2, villa: 2, house: 2, medium: 2, small: 2, apartment: 0, shop: 0 };
