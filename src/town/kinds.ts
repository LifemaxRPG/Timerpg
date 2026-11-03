export interface Rect { x: number; y: number; w: number; h: number }
export interface Road extends Rect { main: boolean; horizontal: boolean }

export type LotKind = 'estate' | 'villa' | 'house' | 'medium' | 'small' | 'apartment' | 'shop';
/** Plot sizes in tiles; they come from the sprite sizes (32px tiles). */
export const SIZES: Record<LotKind, { w: number; h: number }> = {
  estate: { w: 23, h: 16 },
  villa: { w: 9, h: 13 },
  house: { w: 12, h: 15 },
  apartment: { w: 7, h: 13 },
  medium: { w: 8, h: 8 },
  small: { w: 6, h: 6 },
  shop: { w: 7, h: 13 },
};
