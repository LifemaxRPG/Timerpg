import { archetypeOf, type ArchetypeId } from '../sim/archetype';
import { SURNAMES } from '../sim/names';
import { pick, rand, rngFor } from '../sim/rng';
import { siteAt, type TownSite } from './map';

// LORE. Every town has one legend, fixed by the world seed, and the legend points at ANOTHER town
// next door. An old resident who trusts you will tell it and hand you a map; following the map to the
// other town and finding the spot uncovers what really happened, and that town's own legend points
// onward, so the legends form a trail through the whole endless world. Each legend is tied to a year:
// before it happened there is nothing to tell and nothing to find, so travelling in time matters.

export type LoreKind = 'cache' | 'mine' | 'wreck' | 'family' | 'fair' | 'meteor';

export interface Lore {
  /** The town that tells the legend (one legend per town). */
  originId: string;
  originName: string;
  /** The town where the truth lies, and its name. */
  targetId: string;
  targetName: string;
  /** Where in the target town: a landmark, falling back to the fountain if that town lacks it. */
  spot: 'clocktower' | 'post' | 'school' | 'fire' | 'police' | 'hotel' | 'fountain';
  kind: LoreKind;
  /** What happened happened in this year: earlier than that there is nothing yet. */
  year: number;
  title: string;
  /** Told by an old resident of the origin town. */
  legend: string;
  /** Revealed at the spot in the target town. */
  discovery: string;
}

/** What each kind of town is most likely to have legends about. */
const KINDS: Record<ArchetypeId, LoreKind[]> = {
  farm: ['meteor', 'family', 'cache'],
  harbor: ['wreck', 'cache', 'family'],
  mill: ['family', 'fair', 'cache'],
  mining: ['mine', 'cache', 'family'],
  resort: ['fair', 'cache', 'wreck'],
  suburb: ['family', 'meteor', 'fair'],
};
const SPOTS = ['clocktower', 'post', 'school', 'fire', 'police', 'hotel', 'fountain', 'fountain'] as const;
const SHIPS = ['Marianne', 'Dauntless', 'Evelyn Rose', 'Albatross', 'North Star', 'Constance'];
const MINES = ['Lucky Strike', 'Deep Hollow', 'Widow\'s Vein', 'Old Number Nine', 'Copper Queen'];

/** The legend told in `origin` (pure in the world seed and the town's cell). */
export function loreOf(worldSeed: string, origin: TownSite): Lore {
  const r = rngFor(worldSeed, 'lore', origin.id);
  // A neighbouring cell: close enough to walk to.
  const dirs = [[1, 0], [-1, 0], [0, 1], [0, -1], [1, 1], [-1, 1], [1, -1], [-1, -1]] as const;
  const [dx, dy] = pick(r, dirs);
  const target = siteAt(worldSeed, origin.cx + dx, origin.cy + dy);
  const kind = pick(r, KINDS[archetypeOf(origin.seed).id]);
  const year = 1952 + Math.floor(rand(worldSeed, 'lore-year', origin.id) * 42);
  const spot = pick(r, SPOTS);
  const S = pick(r, SURNAMES) as string;
  const O = origin.name, T = target.name;
  let title: string, legend: string, discovery: string;
  switch (kind) {
    case 'cache':
      title = `${S}'s missing savings`;
      legend = `Old ${S}, the banker, vanished in ${year} with the savings of half of ${O}. They say he never left the county: he buried it in ${T}.`;
      discovery = `Behind a loose stone you find a rusted strongbox of ${S}'s ledgers: every name from ${O}, every dollar owed. A note on top reads, "Forgive me."`;
      break;
    case 'mine': {
      const m = pick(r, MINES);
      title = `The ${m} collapse`;
      legend = `The ${m} mine collapsed in ${year} and seven men were never found. The survivors swore the old tunnels ran clear under the hills to ${T}.`;
      discovery = `A boarded-up shaft mouth, and a brass plaque nobody has polished in years: seven names, and the date of the ${m} collapse in ${year}. Someone has left fresh flowers.`;
      break;
    }
    case 'wreck': {
      const ship = pick(r, SHIPS);
      title = `The wreck of the ${ship}`;
      legend = `The ${ship} went down in ${year} carrying a whole winter's wages for ${O}. Her bell washed up on the shore at ${T}.`;
      discovery = `The ${ship}'s bell hangs here, green with age, her name still legible. Scratched underneath: the names of the crew, and "We were almost home."`;
      break;
    }
    case 'family':
      title = `The ${S}s who disappeared`;
      legend = `The ${S} family left ${O} overnight in ${year}, supper still on the table. Their last letter was postmarked ${T}.`;
      discovery = `Tucked behind a bench, a waterproofed envelope: a letter from the ${S}s. They never meant to vanish. They were afraid of someone from ${O}, and they begged whoever found this to keep quiet.`;
      break;
    case 'fair':
      title = `The dark harvest fair`;
      legend = `The ${year} harvest fair ended when every light in ${O} went out at once. They say the Ferris wheel was taken apart that night and carried off to ${T}.`;
      discovery = `Half-buried here, a rusted carnival seat stamped "${O} Harvest Fair ${year}". Nobody in ${T} will say how it came to be here.`;
      break;
    default:
      title = `The thing that fell from the sky`;
      legend = `In ${year} something fell from the sky outside ${O}. The farmer who found it carried it to ${T} and never spoke of it again.`;
      discovery = `A fused, glassy lump of dark metal sits in a niche, warm to the touch, with a scrap of newsprint from ${year}: "STRANGE LIGHT OVER ${O.toUpperCase()}."`;
  }
  return { originId: origin.id, originName: O, targetId: target.id, targetName: T, spot, kind, year, title, legend, discovery };
}

/** Everything for a map the player holds. */
export interface MapEntry {
  loreId: string;
  originName: string;
  targetId: string;
  targetName: string;
  title: string;
  year: number;
}

export const mapOf = (l: Lore): MapEntry => ({ loreId: l.originId, originName: l.originName, targetId: l.targetId, targetName: l.targetName, title: l.title, year: l.year });
