import { stream } from './noise';

// THE NAME REGISTRY (WORLD_GEN.md section 11): one registry of places on the planet, every name unique (a clash
// re-rolls with the next key), so an NPC saying a name means one place and the planner needs no "nearest one" rule.
// The names are made up and American in style: invented roots (syllables that sound like old surnames and settlers'
// words) with the usual endings, nature words with a second word, Port and Mount for harbours and mountain towns.
// A blocklist keeps out well-known real towns the patterns could make; invented roots make most names new words.

// (first syllables of old settler surnames and English place words, and a joining syllable: Selwick, Talbury, Kinmore)
const ROOT = [
  'Ab', 'Ald', 'Ash', 'Ard', 'Bal', 'Bel', 'Bex', 'Bram', 'Bran', 'Brin', 'Cal', 'Carr', 'Chal', 'Cor', 'Dal', 'Dun', 'Dor', 'El',
  'Ev', 'Fal', 'Fen', 'Gar', 'Gil', 'Glen', 'Hal', 'Har', 'Hol', 'Hux', 'Ing', 'Kel', 'Kin', 'Lan', 'Lor', 'Lyn', 'Mal', 'Mar',
  'Mel', 'Nor', 'Ost', 'Pem', 'Pen', 'Quen', 'Ral', 'Ren', 'Ros', 'Sel', 'Stan', 'Sut', 'Tal', 'Tam', 'Tor', 'Ul', 'Ver', 'Wal',
  'Wel', 'Wex', 'Wyn', 'Yar', 'Thur', 'Cran', 'Dex', 'Fox', 'Garn', 'Hesk', 'Kers', 'Lud', 'Mor', 'Nesb', 'Orl', 'Pres',
  'Rad', 'Sax', 'Tem', 'Ux', 'Wend', 'Brack', 'Cold', 'Elk', 'Hart', 'Hen', 'Lock', 'Mill', 'Oak', 'Red', 'Wick', 'Yell',
] as const;
const JOIN = ['', '', '', '', '', 'er', 'en', 'ing', 'ar', 'el', 'ow', 'ey', 'is', 'an', 'ock', 'ford', 'ham', 'ley'] as const;
const ENDING = [
  'ton', 'ville', 'burg', 'ford', 'field', 'dale', 'wood', 'haven', 'mont', 'stead', 'crest', 'view', 'ridge', 'vale', 'bury',
  'ham', 'brook', 'well', 'worth', 'ley', 'by', 'mouth', 'boro', 'more', 'land', 'shire', 'gate', 'port', 'wick', 'combe',
] as const;
const NATURE = [
  'Maple', 'Oak', 'Cedar', 'Elm', 'Ash', 'Pine', 'Birch', 'Willow', 'Hazel', 'Alder', 'Hickory', 'Laurel', 'Juniper', 'Aspen',
  'Sumac', 'Tamarack', 'Sycamore', 'Linden', 'Cottonwood', 'Buckeye', 'Bramble', 'Thistle', 'Clover', 'Heron', 'Plover',
  'Kestrel', 'Badger', 'Otter', 'Elk', 'Bison', 'Antler', 'Flint', 'Slate', 'Granite', 'Quarry', 'Copper', 'Tin', 'Salt',
  'Amber', 'Garnet', 'Juniper', 'Frost', 'Ember', 'Thunder', 'Lantern', 'Anvil', 'Bellows', 'Wagon', 'Saddle', 'Harrow',
] as const;
const SECOND = [
  'Falls', 'Springs', 'Heights', 'Crossing', 'Hollow', 'Landing', 'Bluffs', 'Meadows', 'Corners', 'Grove', 'Point', 'Bend',
  'Flats', 'Gap', 'Prairie', 'Mills', 'Rapids', 'Fork', 'Station', 'Ferry', 'Narrows', 'Ridge', 'Glen', 'Basin', 'Shoals',
] as const;
const HAMLET_SECOND = ['Corners', 'Crossroads', 'Store', 'Mills', 'Station', 'Chapel', 'Gap', 'Bend', 'Fork', 'Crossing', 'Siding', 'Grange'] as const;
const COMPASS = ['North', 'South', 'East', 'West', 'New', 'Old', 'Upper', 'Lower'] as const;

/** Real towns the patterns could otherwise make (kept out: the planet's places are all made up). */
const REAL = new Set([
  'Springfield', 'Fairview', 'Greenville', 'Franklin', 'Clinton', 'Madison', 'Georgetown', 'Salem', 'Riverside', 'Cedar Falls',
  'Maplewood', 'Oakwood', 'Greenwood', 'Fairfield', 'Lakewood', 'Ashland', 'Milford', 'Bristol', 'Oakland', 'Ashford', 'Elmwood',
  'Pinehurst', 'Cedarville', 'Oakdale', 'Elmhurst', 'Pineville', 'Ashton', 'Ashville', 'Asheville', 'Oakville', 'Burlington',
  'Lexington', 'Arlington', 'Princeton', 'Trenton', 'Hampton', 'Stockton', 'Brockton', 'Dayton', 'Canton', 'Clayton', 'Danville',
  'Hartford', 'Bedford', 'Stamford', 'Medford', 'Sanford', 'Stratford', 'Rockford', 'Oxford', 'Woodbury', 'Waterbury', 'Danbury',
  'Shelby', 'Kirby', 'Granby', 'Hollywood', 'Sherwood', 'Wellington', 'Willow Springs', 'Cedar Rapids', 'Elk Grove', 'Oak Ridge',
  'Elm Grove', 'Pine Bluff', 'Sioux Falls', 'Great Falls', 'Twin Falls', 'Elkton', 'Salem', 'Laurel', 'Hazelwood', 'Linden',
  'Aspen', 'Birchwood', 'Cedarburg', 'Hickory', 'Sycamore', 'Juniper', 'Cottonwood', 'Copperton', 'Flint', 'Slater', 'Granite Falls',
  'Saltville', 'Salt Lake', 'Frostburg', 'Amberley', 'Clover', 'Thornton', 'Shelton', 'Dalton', 'Fulton', 'Benton', 'Denton',
  'Kenton', 'Paxton', 'Preston', 'Weston', 'Easton', 'Newton', 'Milton', 'Hilton', 'Kingston', 'Charleston', 'Boston', 'Houston',
  'Austin', 'Portland', 'Westport', 'Newport', 'Bridgeport', 'Rockport', 'Southport', 'Northport', 'Freeport', 'Davenport', 'Lockport',
  'Gulfport', 'Shreveport', 'Stockbridge', 'Woodstock', 'Brookfield', 'Westfield', 'Northfield', 'Plainfield', 'Bloomfield', 'Mansfield',
  'Sheffield', 'Chesterfield', 'Litchfield', 'Deerfield', 'Wakefield', 'Garfield', 'Brookville', 'Brooksville', 'Pittsburg', 'Harrisburg',
  'Blacksburg', 'Gettysburg', 'Vicksburg', 'Lynchburg', 'Hillsboro', 'Greensboro', 'Fayetteville', 'Knoxville', 'Louisville', 'Nashville',
  'Jacksonville', 'Gainesville', 'Huntsville', 'Evansville', 'Belleville', 'Centreville', 'Centerville', 'Hamilton', 'Mount Vernon',
  'Mount Pleasant', 'Mount Holly', 'Mount Airy', 'Mount Shasta', 'Port Arthur', 'Port Huron', 'Port Angeles', 'Port Jervis', 'Glendale',
  'Rosedale', 'Riverdale', 'Hillsdale', 'Ferndale', 'Oakhurst', 'Woodland', 'Highland', 'Lakeland', 'Kirkland', 'Ashby', 'Rockland',
  'Maple Grove', 'Maple Heights', 'Oak Grove', 'Cedar Grove', 'Pine Grove', 'Willow Grove', 'Elk Rapids', 'Grand Rapids', 'Big Rapids',
  'Badger', 'Otter Creek', 'Bison', 'Flintstone', 'Quarryville', 'Tin City', 'Amber', 'Garnet', 'Ember', 'Anvil', 'Saddle River',
  'Harrow', 'Wagon Mound', 'Clarksville', 'Columbia', 'Washington', 'Jefferson', 'Lincoln', 'Monroe', 'Jackson', 'Marion', 'Auburn',
  'Dover', 'Concord', 'Camden', 'Chester', 'Marlborough', 'Marlboro', 'Wexford', 'Darlington', 'Burlington', 'Shelbyville', 'Quincy',
]);

export type NameStyle = 'capital' | 'port' | 'mountain' | 'river' | 'mill' | 'farm' | 'harbor' | 'mining' | 'resort' | 'suburb' | 'hamlet';

function root(r: () => number): string {
  const pick = <T,>(a: readonly T[]): T => a[Math.floor(r() * a.length)]!;
  const a = pick(ROOT), j = pick(JOIN);
  return a + (a.endsWith(j.slice(0, 1)) ? '' : j);
}

/** One candidate name for a place of [style], try [n] of key [key]. */
export function candidateName(seed: string, key: string, style: NameStyle, n: number): string {
  const r = stream(seed, 'name', key, n);
  const pick = <T,>(a: readonly T[]): T => a[Math.floor(r() * a.length)]!;
  const s = r();
  const rt0 = root(r);
  const rt = rt0;
  const glue = (e: string): string => (/(ford|ham|ley)$/.test(rt0) ? rt0 : rt0 + e);
  const word = r() < 0.55 ? rt : pick(NATURE);
  const ending = pick(ENDING.filter((e) => !word.toLowerCase().endsWith(e.slice(0, 2))));
  let name: string;
  if (style === 'hamlet') name = s < 0.55 ? `${word} ${pick(HAMLET_SECOND)}` : s < 0.8 ? glue(ending) : `${rt}'s ${pick(['Corner', 'Store', 'Mill', 'Crossing'])}`;
  else if ((style === 'port' || style === 'harbor') && s < 0.45) name = `Port ${s < 0.25 ? rt : word}`;
  else if ((style === 'mountain' || style === 'mining') && s < 0.4) name = `Mount ${word}`;
  else if ((style === 'mill' || style === 'river') && s < 0.35) name = `${word} ${pick(['Falls', 'Rapids', 'Mills', 'Ferry', 'Bend', 'Fork'])}`;
  else if (s < 0.68) name = glue(ending);
  else if (s < 0.93) name = `${word} ${pick(SECOND)}`;
  else name = `${pick(COMPASS)} ${glue(ending)}`;
  return name.charAt(0).toUpperCase() + name.slice(1);
}

// (wonders: a descriptive or nature word, or an invented root, with the kind's own word: Thunder Falls, Kelmar Canyon)
const WONDER_WORD = [
  'Silver', 'Thunder', 'Bridal', 'Whisper', 'Echo', 'Crystal', 'Painted', 'Vermilion', 'Sapphire', 'Golden', 'Rose', 'Lantern',
  'Hollow', 'Raven', 'Eagle', 'Cloud', 'Mist', 'Rainbow', 'Ember', 'Moon', 'Star', 'Sunrise', 'Shadow', 'Giant', 'Lost', 'Hidden',
  'Singing', 'Weeping', 'Blue', 'White', 'Red', 'Black', 'Amber', 'Jade', 'Coral', 'Ivory', 'Copper', 'Iron', 'Chimney', 'Organ',
  'Cathedral', 'Devil', 'Angel', 'Widow', 'Lion', 'Bear', 'Elk', 'Heron', 'Falcon', 'Otter', 'Willow', 'Cedar', 'Juniper', 'Aspen',
] as const;
const WONDER_SUFFIX: Record<string, readonly string[]> = {
  'blossom valley': ['Valley', 'Vale', 'Blossom Valley', 'Hollow'], meadow: ['Meadows', 'Meadow', 'Pastures'], superbloom: ['Flower Flats', 'Bloom Basin', 'Poppy Fields'],
  waterfall: ['Falls', 'Falls', 'Cascade', 'Veil Falls'], canyon: ['Canyon', 'Gorge', 'Canyon'], arches: ['Arches', 'Spires', 'Hoodoos', 'Needles'],
  karst: ['Towers', 'Pinnacles', 'Stone Forest'], 'table mountain': ['Tableland', 'Table Mountain'], volcano: ['Crater', 'Cone', 'Peak'],
  'hot springs': ['Hot Springs', 'Geysers', 'Terraces'], glacier: ['Glacier', 'Icefield'], fjord: ['Fjord', 'Sound', 'Inlet'],
  'giant trees': ['Grove', 'Giants', 'Big Trees'], 'salt flat': ['Salt Flat', 'Salt Pan', 'Mirror Flats'], 'sea stacks': ['Stacks', 'Sea Arch', 'Needles'],
  atoll: ['Atoll', 'Lagoon'], 'glowing bay': ['Bay', 'Cove'], fireflies: ['Glade', 'Firefly Meadow'], aurora: ['Lights Point', 'Sky Hill'],
  cave: ['Caverns', 'Caves'],
};

/** One candidate name for a wonder of [kind], try [n] of key [key]. */
export function candidateWonderName(seed: string, key: string, kind: string, n: number): string {
  const r = stream(seed, 'wonder-name', key, n);
  const pick = <T,>(a: readonly T[]): T => a[Math.floor(r() * a.length)]!;
  const s = r();
  const word = s < 0.5 ? pick(WONDER_WORD) : s < 0.75 ? pick(NATURE) : root(r);
  const suffix = pick(WONDER_SUFFIX[kind] ?? ['Wonder']);
  if (kind === 'volcano' && r() < 0.5) return `Mount ${word}`;
  if (kind === 'giant trees' && r() < 0.3) return `${word} Redwoods`;
  return `${word.charAt(0).toUpperCase()}${word.slice(1)} ${suffix}`;
}

/** The registry: names in the order they are claimed (hubs first, then towns, then hamlets). */
export class NameRegistry {
  private readonly used = new Set<string>();
  constructor(readonly seed: string) {}
  /** Claims an authored name (e.g. the capital's). */
  claim(name: string): string {
    if (this.used.has(name.toLowerCase())) throw new Error(`name ${name} is taken`);
    this.used.add(name.toLowerCase());
    return name;
  }
  make(key: string, style: NameStyle): string {
    for (let n = 0; n < 200; n++) {
      const c = candidateName(this.seed, key, style, n);
      if (this.used.has(c.toLowerCase()) || REAL.has(c)) continue;
      this.used.add(c.toLowerCase());
      return c;
    }
    throw new Error(`no free name for ${key}`);
  }
  /** A wonder's name (unique among every place and wonder on the planet). */
  makeWonder(key: string, kind: string): string {
    for (let n = 0; n < 400; n++) {
      const c = candidateWonderName(this.seed, key, kind, n);
      if (this.used.has(c.toLowerCase()) || REAL.has(c)) continue;
      this.used.add(c.toLowerCase());
      return c;
    }
    throw new Error(`no free name for ${key}`);
  }
  has(name: string): boolean { return this.used.has(name.toLowerCase()); }
}

export const isRealTownName = (n: string): boolean => REAL.has(n);
