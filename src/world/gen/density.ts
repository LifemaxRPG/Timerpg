// THE PLACES' DENSITY (kotlin-street/docs/TOWNS_EVERYWHERE.md "Density"): every knob that decides how many places the
// planet has and how far apart they stand, in one table, so it can be tuned after the owner has seen the towns. The
// placers read these values and nothing else (places.ts for hubs, towns and mountain places, roads.ts for hamlets).
//
// The values are the ones the world was generated with up to 2026-11-23 (alpha, bravo and charlie came out of them):
// changing one changes the world (every place after the first one it moves, and so every baked town).
//
// Measured on alpha (tools/towns-density.ts): see TOWNS_EVERYWHERE.md for the nearest-neighbour spacing and the drive
// and rail times between places by rank.

/**
 * THE SETTING, one knob per rank (1 = the world as generated up to 2026-11-23): a multiplier on that rank's spacing (2
 * spreads them twice as far apart, so about a quarter as many; 0.7 about twice as many). `town` spaces every town
 * (major towns, towns and villages are drawn from one spacing, then ranked by the shares in DENSITY.rank: raise
 * `rank.town` for fewer villages and more towns); `mountain` the alpine villages, pass hamlets and mining towns;
 * `hamlet` the hamlets along the roads.
 */
export const SPACING = { hub: 1, town: 1, mountain: 1, hamlet: 1 };

export const DENSITY = {
  /** Hubs (hand-made cities): km between any two on a continent; ports among themselves. */
  hub: { sepKm: 70, portSepKm: 120 },
  /** Towns (Poisson disk weighted by suitability): km apart on the best land (min) and on the poorest (max); none this
   * near a hub. */
  town: { minKm: 12, maxKm: 25, hubKm: 15 },
  /** A town's rank by a roll: below `major` a major town, below `town` a town, else small (a village). */
  rank: { major: 0.06, town: 0.45 },
  /** Mountain places (alpine villages, pass hamlets, mining towns): km from every other place. */
  mountain: { alpineKm: 8, passKm: 10, mineKm: 10 },
  /** Hamlets along the roads: one every `everyKm` to `everyKm + spreadKm` of road, none within `clearKm` of another place. */
  hamlet: { everyKm: 4, spreadKm: 4, clearKm: 3.5 },
} as const;
