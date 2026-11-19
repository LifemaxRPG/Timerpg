import { pick, rand, rngFor, type Rng } from '../sim/rng';

// THE FILMS. One made-up catalogue of pictures for the whole game world, released year by year (every town's cinema
// shows the same year's releases, as real towns did). Titles, taglines and credits are built from word pools per decade
// and genre so they sound of their time, but none is a real film: no real titles, studios, actors or brands. The
// catalogue is a pure function of the year (generated in order from FIRST, so no title is ever used twice).

export type Decade = 1950 | 1960 | 1970 | 1980 | 1990 | 2000 | 2010 | 2020;
export const DECADES: readonly Decade[] = [1950, 1960, 1970, 1980, 1990, 2000, 2010, 2020];

export interface Poster {
  /** The decade's poster look, for the painter: see POSTER_STYLES. */
  style: string;
  /** What the picture shows (a generic genre motif: a creature, a rider, a rocket, a face...). */
  motif: string;
  /** Four colours, '#rrggbb': background, main, accent, lettering. */
  palette: [string, string, string, string];
  /** The painter's own random seed for this poster. */
  seed: number;
}

export interface Film {
  title: string;
  genre: string;
  tagline: string;
  /** The rating board's mark ('G', 'PG', 'PG-13', 'R'); none before ratings began in 1968. */
  rating?: string;
  year: number;
  /** A made-up star billed above the title. */
  star: string;
  poster: Poster;
}

/** Each genre in plain words, for people talking about the pictures ("a western", "a monster picture"). */
export const GENRE_WORDS: Record<string, string> = {
  monster: 'a monster picture', western: 'a western', musical: 'a musical', scifi: 'a space picture', noir: 'a crime picture',
  epic: 'an epic', spy: 'a spy picture', beach: 'a beach party picture', gothic: 'a horror picture', disaster: 'a disaster picture',
  chase: 'a car chase picture', cop: 'a cop picture', space: 'a space adventure', conspiracy: 'a thriller', kungfu: 'a kung fu picture',
  action: 'an action movie', slasher: 'a horror movie', teen: 'a teen comedy', fantasy: 'a fantasy adventure', buddy: 'a buddy cop movie',
  thriller: 'a thriller', family: 'a family movie', romcom: 'a romantic comedy', crime: 'a crime movie', animated: 'a cartoon',
  found: 'a horror movie', heist: 'a heist movie', comedy: 'a comedy', heroes: 'a superhero movie', dystopia: 'a science fiction movie',
  horror: 'a horror movie', multiverse: 'a science fiction movie', drama: 'a drama', biopic: 'a biopic',
};

/** How each decade's posters look (the painter in Kotlin keys on these). */
export const POSTER_STYLES: Record<Decade, string> = {
  1950: 'painted-pulp', // illustrated, painted figures, huge hand-lettered title, a screaming tagline
  1960: 'painted-epic', // painted panoramas, cast montages, serif titles
  1970: 'airbrush', // airbrushed scenes, chrome or flame lettering, a big central image
  1980: 'neon-grid', // dark backgrounds, neon outlines, laser grids, chrome type
  1990: 'photo-montage', // photographed faces in a row, bold sans titles, a dark sky
  2000: 'blockbuster', // orange and blue, the hero walking away from an explosion
  2010: 'teal-orange', // teal and orange grading, a lone figure, a skyline, thin type
  2020: 'minimal', // flat colour fields, one symbol, small type
};

const FIRST = 1950;
const LAST = 2040;
/** Releases a year: enough for an eight-screen multiplex and its coming-attractions wall. */
export const RELEASES = 16;
const SEED = 'timerpg-films';

// ------------------------------------------------------------------------------------------------ word pools
/** Made-up places, people and things shared by the genres. */
const SHARED: Record<string, readonly string[]> = {
  town: ['Dry Fork', 'Coyote Wells', 'Juniper Gap', 'Bitter Spring', 'Mesa Rosa', 'Cutler\'s Ford', 'Silver Bend', 'Red Hollow', 'Sawtooth', 'Paloma Flats', 'Gallows Creek', 'Hatcher\'s Pass', 'Copper Mesa', 'Blind Elk', 'Tamarack', 'Sundown Wells'],
  place: ['Lake Tarrow', 'Marrow Creek', 'Dunmore', 'Ashby Point', 'Crow Lake', 'Pell Harbor', 'Glen Orra', 'Fennick Island', 'Hollis Bay', 'Larkmoor', 'Cold Spring Ridge', 'Wrenfield', 'Bramley Falls', 'Corwin Heights', 'Saltmarsh Point', 'Gilder Bay'],
  city: ['Port Velin', 'New Carthay', 'Marlowe City', 'Brightwater', 'Kessington', 'Oriel', 'San Lucerio', 'Halvard', 'Cape Morrow', 'Linden Bay', 'Vossberg', 'Calder City'],
  first: ['Johnny', 'Rusty', 'Lola', 'Vera', 'Duke', 'Dottie', 'Hank', 'Mabel', 'Clint', 'Rosalind', 'Buck', 'Jolene', 'Max', 'Nora', 'Sonny', 'Ruby', 'Walt', 'Ginger', 'Dex', 'Cassie', 'Theo', 'Mira', 'Jax', 'Wren'],
  last: ['Kessler', 'Calloway', 'Tremaine', 'Holloway', 'Vance', 'Mercer', 'Dalloway', 'Strickland', 'Bellamy', 'Ashcroft', 'Rourke', 'Pembrook', 'Gallagher', 'Thorne', 'Whitlock', 'Delacroix', 'Fairbanks', 'Marchetti', 'Okafor', 'Lindqvist'],
  number: ['Two', 'Three', 'Four', 'Five', 'Six', 'Seven', 'Nine', 'Twelve'],
  planet: ['Zorrax', 'Venusia', 'the Ninth Moon', 'Planet Ulmo', 'Krell-9', 'the Dark Star Belt', 'Saturnia', 'Planet Q', 'the Red Nebula', 'Orbis Prime', 'Neptura', 'Kepheus'],
  month: ['August', 'October', 'December', 'March', 'June'],
  critter: ['Badger', 'Platypus', 'Hedgehog', 'Walrus', 'Gecko', 'Narwhal', 'Otter', 'Moth', 'Armadillo', 'Puffin', 'Lemur', 'Toad'],
};

interface GenreSpec {
  /** Title patterns: {slot} draws from the genre's words or SHARED. */
  titles: readonly string[];
  words?: Record<string, readonly string[]>;
  taglines: readonly string[];
  motif: string;
  /** Ratings this genre gets once ratings exist (weighted by repetition). */
  ratings: readonly string[];
  weight?: number;
  /** Can return as a sequel. */
  sequels?: boolean;
}

const FAMILY = ['G', 'G', 'PG'];
const GENERAL = ['PG', 'PG', 'PG-13'];
const ADULT = ['R', 'R', 'PG-13'];
const ACTION = ['PG-13', 'R', 'PG-13'];

const GENRES: Record<Decade, Record<string, GenreSpec>> = {
  1950: {
    monster: {
      titles: ['The {creature} from {place}', '{creature} Over {place}', 'The {creature} That Ate {place}', 'Night of the {creature}', 'It Crawled Out of {place}', 'Return of the {creature}', 'Terror of the {creature}', 'The Thing in {place}'],
      words: { creature: ['Moss Men', 'Glass Spider', 'Tar Beast', 'Swamp Leech', 'Lava Toad', 'Brain Eels', 'Ice Walker', 'Mole Giants', 'Fungus People', 'Sea Hag', 'Crater Crawler', 'Fifty-Ton Beetle', 'Iron Wasp', 'Mud Colossus', 'Gill Woman'] },
      taglines: ['It came from the deep... and it is hungry!', 'No town is safe! No door can hold it!', 'Science made it. Nothing can stop it!', 'See it rise from the black water!', 'The screen\'s most fearsome creature!'],
      motif: 'creature', ratings: [], weight: 3, sequels: true,
    },
    western: {
      titles: ['Showdown at {town}', 'Last Stage to {town}', '{number} Bullets for {last}', 'Ride to {town}', 'The Marshal of {town}', 'Powder Smoke over {town}', 'Saddle Tramp of {town}', 'The {last} Brothers Ride Again', 'Spurs and Sixguns'],
      taglines: ['One man. One gun. One chance.', 'He rode in alone... he would ride out a legend!', 'The West was never wilder!', 'His badge was all the law {town} had!'],
      motif: 'rider', ratings: [], weight: 3,
    },
    musical: {
      titles: ['Moonlight over {place}', 'Two Hearts in {city}', 'A Waltz for {first}', 'Kisses in {city}', 'Sing, {first}, Sing!', 'The {first} {last} Story', 'Holiday in {city}', 'Swing Shift Sweethearts', 'Ten Thousand Tap Shoes'],
      taglines: ['Songs! Romance! Spectacle!', 'The happiest musical of the year!', 'In glorious color!', 'You\'ll be humming it for a month!'],
      motif: 'dancers', ratings: [], weight: 2,
    },
    scifi: {
      titles: ['Rocket to {planet}', 'Invaders from {planet}', 'Beyond {planet}', 'The Day the Sky Turned Green', 'Saucer Men of {planet}', 'Atomic Girl from {planet}', 'Rocket Cadet {last}', 'The Moon Is Ours!'],
      taglines: ['From outer space... a warning!', 'The year 2000 is here!', 'They came from beyond the stars!', 'See the future... if you dare!'],
      motif: 'rocket', ratings: [], weight: 2, sequels: true,
    },
    noir: {
      titles: ['Shadows on {city} Street', 'The {last} Alibi', 'Dead by {month}', 'Never Trust {first}', 'The Red Lipstick Murders', 'One Way out of {city}', 'Ninety Minutes to Midnight'],
      taglines: ['She was trouble with a capital T!', 'The city never sleeps... and neither does murder!', 'A dame, a gun and one bad night.'],
      motif: 'silhouette', ratings: [], weight: 1,
    },
  },
  1960: {
    epic: {
      titles: ['{hero} of {ancient}', 'The Fall of {ancient}', '{number} Swords for {ancient}', 'The Lion of {ancient}', 'The Golden Gates of {ancient}', 'Chariots over {ancient}', 'Queen of the {ancient} Sea'],
      words: { hero: ['Taurion', 'Valerius', 'Cassandor', 'Brutarius', 'Aurelia', 'Demetrios'], ancient: ['Tyrennia', 'Valdoria', 'Carthemis', 'Aurelion', 'Myrrhos', 'Pellatine'] },
      taglines: ['A cast of ten thousand!', 'The mightiest spectacle ever filmed!', 'An empire\'s fate in one man\'s hands!', 'Three years in the making!'],
      motif: 'colossus', ratings: GENERAL, weight: 2,
    },
    spy: {
      titles: ['Agent {codename}', '{codename} Strikes at Dawn', 'Operation {codeword}', 'The {last} Dossier', 'A Diamond for {codename}', 'Code Name: {codeword}', 'The Man with the Velvet Glove'],
      words: { codename: ['Silverfox', 'Nightjar', 'Mister Velvet', 'Domino Seven', 'Cobalt', 'Harlequin'], codeword: ['Nightjar', 'Tinsel', 'Zebra Bay', 'Marmalade', 'Glass Swan', 'Blue Lantern'] },
      taglines: ['Women love him. Villains fear him!', 'The deadliest agent alive!', 'Shaken, stirred, and never caught!', 'The world\'s fate hangs by a thread!'],
      motif: 'agent', ratings: GENERAL, weight: 3, sequels: true,
    },
    beach: {
      titles: ['Surfin\' {place}', 'Bikini Bandstand', 'Twist Party at {place}', 'How to Sink a Sailboat', 'Beach Blanket Ballyhoo', 'Go-Go Girls of {place}', 'Wild Weekend at {place}'],
      taglines: ['Sun, surf and swingin\' fun!', 'The grooviest party on the sand!', 'Bring your board and your baby!'],
      motif: 'surfer', ratings: FAMILY, weight: 2,
    },
    gothic: {
      titles: ['The Crypt of Count {count}', 'The Mask of {count}', 'Curse of the {last} Manor', 'The Bride of Count {count}', 'Blood Moon over {place}', 'The {count} Inheritance'],
      words: { count: ['Malvezzi', 'Vorhane', 'Draskovic', 'Moravian', 'Ulric', 'Szekely'] },
      taglines: ['Do not watch alone!', 'The dead do not rest at {place}!', 'In terrifying color!'],
      motif: 'castle', ratings: ADULT, weight: 2, sequels: true,
    },
    western: {
      titles: ['A Coffin for {last}', '{number} Graves for {last}', 'The Hired Gun of {town}', 'Dust on the Saddle', 'A Bullet for {first}', 'The Long Ride to {town}'],
      taglines: ['He had no name. He needed none.', 'Revenge rides tall in the saddle!', 'Every bullet had a name on it.'],
      motif: 'rider', ratings: GENERAL, weight: 2,
    },
  },
  1970: {
    disaster: {
      titles: ['Inferno at {tower}', 'Tremor {numeral}', '{ship} Down', 'Flight {flight}', 'The {last} Dam Disaster', 'Tidal Wave over {city}', 'Earthquake Day in {city}'],
      words: { tower: ['Meridian Tower', 'the Halvard Building', 'the Glass Spire', 'Crown Plaza Tower'], ship: ['S.S. Paloma', 'Ocean Queen', 'Northern Star', 'S.S. Halcyon'], numeral: ['7', '8.4', '9'], flight: ['209', '517', '330'] },
      taglines: ['Two hundred floors. One way down.', 'Nowhere to run. Nowhere to hide.', 'The disaster that shook the world!', 'Who will survive?'],
      motif: 'flames', ratings: GENERAL, weight: 2, sequels: true,
    },
    chase: {
      titles: ['Hot Asphalt', 'Highway {flight} Run', 'Diesel Danny', '{first} and the Smokey Run', 'Two-Lane Thunder', 'The Big Rig Getaway', 'Burnout at {town}', 'Gearjammer'],
      words: { flight: ['66', '41', '9'] },
      taglines: ['Pedal to the metal!', 'They\'re hauling more than cargo!', 'Ten states. Twelve cops. One wild ride.'],
      motif: 'car', ratings: GENERAL, weight: 3, sequels: true,
    },
    cop: {
      titles: ['{last} on the Edge', 'The {city} Connection', 'Badge {flight}', 'A Cop Called {first}', 'No Deals for {last}', 'Precinct Forty Blues'],
      words: { flight: ['714', '23', '1209'] },
      taglines: ['He plays by his own rules.', 'The city\'s toughest cop. The city\'s worst day.', 'Justice comes with a .44.'],
      motif: 'gun', ratings: ADULT, weight: 2, sequels: true,
    },
    space: {
      titles: ['The {planet} Rebellion', 'Voyage to {planet}', 'Starship {ship}', 'Galaxy Rangers of {planet}', 'The Last Pilot of {planet}'],
      words: { ship: ['Arcturus', 'Valiant Nine', 'Odyssa', 'Pathfinder'] },
      taglines: ['Somewhere beyond the stars, a war is coming.', 'The greatest adventure in the galaxy!', 'Space will never be the same!'],
      motif: 'planet', ratings: FAMILY, weight: 2, sequels: true,
    },
    conspiracy: {
      titles: ['The {last} Files', 'The {month} Memo', 'The {city} Tapes', 'Witness at {place}', 'The Man in Room Nine'],
      taglines: ['Trust no one.', 'The truth is buried in Washington.', 'He knew too much. Now they know he knows.'],
      motif: 'eye', ratings: GENERAL, weight: 1,
    },
    kungfu: {
      titles: ['Fist of the Silver Heron', 'The {number} Masters of {city}', 'Iron Palm Revenge', 'Jade Gate Tournament', 'Kick of the Jade Tiger'],
      taglines: ['His hands are lethal weapons!', 'The master is back... and he\'s angry!'],
      motif: 'fighter', ratings: ADULT, weight: 1, sequels: true,
    },
  },
  1980: {
    action: {
      titles: ['Chrome {thing}', '{last}: Maximum Force', 'Thunder Run {numeral}', 'Code Red: {city}', 'The {last} Protocol', 'Laser Commando', 'Overdrive Squad'],
      words: { thing: ['Sentinel', 'Vendetta', 'Fury', 'Avenger'], numeral: ['Delta', 'Zero', 'X'] },
      taglines: ['He\'s back. And this time it\'s war.', 'No rules. No mercy. No sequel... yet.', 'Justice has a new weapon.'],
      motif: 'hero', ratings: ACTION, weight: 3, sequels: true,
    },
    scifi: {
      titles: ['Neon {thing}', 'Station {numeral} on {planet}', 'The Omega Circuit', '{planet} Drift', 'Cyberdome {numeral}', 'Holoworld', 'The Last Android'],
      words: { thing: ['Ronin', 'Dawn', 'Phantoms', 'Run'], numeral: ['9', '12', 'Omega'] },
      taglines: ['In the future, the only law is the machine.', 'Enter the zone.', 'The year is 2019. Hope is obsolete.'],
      motif: 'grid', ratings: GENERAL, weight: 2, sequels: true,
    },
    slasher: {
      titles: ['Sleepover at {place}', 'Homecoming at {place}', 'The {weekday} Night Visitor', 'Summer Camp {place}', 'Don\'t Open the Boathouse', 'Class Reunion at {place}', 'The Babysitter Is Not Alone'],
      words: { weekday: ['Tuesday', 'Wednesday', 'Thursday'] },
      taglines: ['Some campers never go home.', 'They should have stayed in bed.', 'Lock the doors. It won\'t help.'],
      motif: 'knife', ratings: ['R'], weight: 2, sequels: true,
    },
    teen: {
      titles: ['{first}\'s Totally Rad Summer', 'Spring Break at {place}', 'Detention Hall Rebels', 'The Mall Rats of {city}', 'Prom King {first}', 'Senior Skip Day Disaster', 'Radical Rollerdisco'],
      taglines: ['School\'s out. Rules are out.', 'The most excellent summer ever!', 'Totally, like, the best movie ever.'],
      motif: 'teens', ratings: GENERAL, weight: 2, sequels: true,
    },
    fantasy: {
      titles: ['Quest for the Amber Crown', '{hero} and the Sword of {realm}', 'The Wizard of {realm}', 'Beyond the Crystal Gate', 'The Unicorn Road', 'The Dragonsbane Saga'],
      words: { hero: ['Thane', 'Kyra', 'Borin', 'Elowen'], realm: ['Elandor', 'Mirrowmere', 'Gallowdeep', 'Kaer Myth'] },
      taglines: ['A world of magic. A hero of destiny.', 'The legend begins.', 'Where swords are drawn and spells are cast.'],
      motif: 'sword', ratings: FAMILY, weight: 2, sequels: true,
    },
    buddy: {
      titles: ['{last} and {last2}', '{number} Hours in {city}', 'Partners in Crime Prevention', 'Mismatched Badges', 'Two Badges in {city}'],
      words: { last2: ['Lopez', 'Kowalski', 'Bishop', 'Nakamura', 'Fletcher'] },
      taglines: ['One plays by the book. One burned the book.', 'They can\'t stand each other. Good thing they\'re cops.'],
      motif: 'duo', ratings: ACTION, weight: 2, sequels: true,
    },
  },
  1990: {
    thriller: {
      titles: ['The {last} Witness', 'Silent Partner in {city}', 'Cold Intent', 'Past Midnight in {city}', 'Fatal Proof', 'The Glass Juror', 'The Juror in Seat Nine', 'The {last} Affair', 'A Stranger in {city}', 'Nobody Leaves {place}'],
      taglines: ['Everyone has a secret. Some will kill for it.', 'The perfect crime. The perfect alibi.', 'Look closer.'],
      motif: 'face', ratings: ADULT, weight: 3,
    },
    family: {
      titles: ['{pet}: A Dog\'s Tale', 'My Dog the Detective', 'The Ferret Who Saved Christmas', 'Home Run Hamster', '{pet} Goes to {city}', 'Little Moose on the Prairie', 'Pelican Rescue', 'The {critter} Who Saved Christmas', '{pet} Saves the Farm'],
      words: { pet: ['Biscuit', 'Waffles', 'Captain Paws', 'Noodle', 'Sir Barksalot'] },
      taglines: ['A hero with four paws!', 'Fun for the whole family!', 'This summer, the dog is the boss.'],
      motif: 'animal', ratings: FAMILY, weight: 3, sequels: true,
    },
    action: {
      titles: ['{number} Minutes to Impact', 'Rooftop {city}', 'Bridge Point Zero', 'Countdown in {city}', 'Runaway Monorail', 'Overpass Ninety', 'Hostage at {place}', '{last}: Final Countdown'],
      taglines: ['The clock is ticking.', 'Get ready for the ride of your life.', 'One man stands between them and the city.'],
      motif: 'explosion', ratings: ACTION, weight: 3, sequels: true,
    },
    romcom: {
      titles: ['Just Friends in {city}', 'Second Date', 'Bridesmaid Number Five', 'Ten Dates in {city}', 'Married by Monday', 'A Diner in {city}', 'Christmas in {place}', 'The Girl from {city}'],
      taglines: ['Sometimes love is right next door.', 'She had a plan. Then she met him.', 'Falling in love was never on the list.'],
      motif: 'couple', ratings: GENERAL, weight: 2,
    },
    disaster: {
      titles: ['Asteroid {rock}', 'Hurricane {first}', 'Hurricane Season in {city}', 'Comet {rock}'],
      words: { rock: ['Hollis', 'XR-12', 'Calder', 'Vega Nine'] },
      taglines: ['Heaven help us.', 'The countdown has begun.', 'Nature has a new temper.'],
      motif: 'planet', ratings: GENERAL, weight: 1,
    },
    crime: {
      titles: ['{first}\'s Last Score', 'Pawnshop Tuesday', 'The {city} Shuffle', 'Nine Bags of Money', 'Bad Day for {first}'],
      taglines: ['A simple job. A very bad day.', 'Crime pays. Badly.'],
      motif: 'money', ratings: ['R'], weight: 1,
    },
  },
  2000: {
    epic: {
      titles: ['The {realm} Chronicles: {sub}', 'Kingdom of {realm}', 'The Banners of {realm}', '{realm}: Rise of the {foe}', 'The Ember Crown of {realm}', 'The Siege of {realm}', 'Heirs of {realm}'],
      words: { realm: ['Elandor', 'Valdoria', 'Thornmere', 'Aldemar', 'Wyrmhold'], sub: ['The Ember Crown', 'Winter\'s Oath', 'The Sunken King', 'The Seventh Gate'], foe: ['Shadow King', 'Ash Riders', 'Bone Legion'] },
      taglines: ['Destiny has a new hero.', 'The battle for the realm begins.', 'Every legend has a beginning.'],
      motif: 'army', ratings: ACTION, weight: 3, sequels: true,
    },
    animated: {
      titles: ['The Snail Who Wanted to Fly', 'Penguin Patrol Academy', 'Toaster Town', 'Robo-Pup', 'Gnome Sweet Gnome Wars', 'The Great Acorn Heist', 'Monster Day Care', '{critter} Island', 'The {critter} Who Saved {city}', '{critter} Academy'],
      taglines: ['This summer, size doesn\'t matter.', 'Fun for the whole family!', 'From the makers of nothing you have seen before.'],
      motif: 'cartoon', ratings: FAMILY, weight: 3, sequels: true,
    },
    found: {
      titles: ['The {place} Tapes', 'Camcorder {numeral}', 'Recovered Footage', 'Night Vision at {place}', 'Basement Cam', 'The {last} House Recordings', 'Last Upload from {place}'],
      words: { numeral: ['17', '9', '33'] },
      taglines: ['The footage was never meant to be seen.', 'Based on footage that was found.'],
      motif: 'static', ratings: ['R'], weight: 2, sequels: true,
    },
    heist: {
      titles: ['The {city} Job', 'Vault Nine', 'The Casino Trick', 'Smash and Grab in {city}', 'The {last} Crew', '{number} Thieves of {city}', 'The {last} Getaway'],
      taglines: ['Twelve thieves. One vault. No second chances.', 'The ultimate score.'],
      motif: 'vault', ratings: ACTION, weight: 2, sequels: true,
    },
    disaster: {
      titles: ['The Shelf Breaks', '{city} Under Water', 'Solar Wrath', 'Magma Rising', 'Volcano Season at {place}', 'The {city} Blackout', 'Meltdown at {place}'],
      taglines: ['The end of the world was just the beginning.', 'Nowhere is safe.'],
      motif: 'wave', ratings: GENERAL, weight: 1,
    },
    comedy: {
      titles: ['Moose on the Loose in {city}', 'Bachelor Weekend in {city}', 'Road Trip to {city}', 'The Groomsmen Go Camping', 'Fantasy League Fever', 'The {last} Family Reunion', '{first} Gets Promoted'],
      taglines: ['Some guys never grow up.', 'The funniest movie of the year!'],
      motif: 'group', ratings: ACTION, weight: 2, sequels: true,
    },
  },
  2010: {
    heroes: {
      titles: ['{hero}: {sub}', 'The {hero} Initiative', 'League of the {team}', '{hero} Returns', 'Rise of the {team}'],
      words: { hero: ['Nightmoth', 'Captain Meridian', 'Voltstrike', 'Iron Sparrow', 'Lady Quasar', 'Graviton'], sub: ['Fractured Sky', 'Endgame Protocol', 'Shadow Cell', 'First Light', 'Dark Orbit'], team: ['Vanguard Six', 'Sentinel Order', 'Pulse Squad'] },
      taglines: ['Every hero has an origin.', 'The universe needs saving. Again.', 'Assemble the team.'],
      motif: 'hero', ratings: ['PG-13'], weight: 4, sequels: true,
    },
    dystopia: {
      titles: ['The {color} Selection', 'Sector {numeral}', 'The {thing} Trials', 'The Outlands Accord', 'Divided City', 'The Walls of {city}', 'Children of Sector {numeral}'],
      words: { color: ['Scarlet', 'Silver', 'Ashen'], numeral: ['Nine', 'Zero', 'Twelve'], thing: ['Ember', 'Glass', 'Tithe'] },
      taglines: ['One girl will change everything.', 'Choose your faction. Choose your fate.'],
      motif: 'silhouette', ratings: ['PG-13'], weight: 2, sequels: true,
    },
    animated: {
      titles: ['Sockmonsters', 'Cloud Kingdom', 'The Little Lighthouse', 'Pip and the Feather Storm', 'Robot Grandpa', 'Kingdom of Cats', '{critter} and the Rainbow Road', 'The {critter} Prince', 'Super {critter}'],
      taglines: ['Big adventure. Little heroes.', 'Fun for the whole family!'],
      motif: 'cartoon', ratings: FAMILY, weight: 3, sequels: true,
    },
    horror: {
      titles: ['It Knocks at Three', 'The Whispering Room', 'Don\'t Answer the Landline', 'The Ones Upstairs', 'Lights Off at {place}', 'The {last} Haunting', 'The Curse of {place}', 'Something in {place}', 'The {last} Tapes'],
      taglines: ['From the producers of your worst nightmares.', 'Once you hear it, it is already too late.'],
      motif: 'shadow', ratings: ['R'], weight: 2, sequels: true,
    },
    space: {
      titles: ['Signal from {planet}', 'Stranded on {planet}', 'Deep Orbit Seven', 'The Long Dark Between', 'Last Light on {planet}', 'Mission to {planet}', 'The {planet} Protocol'],
      taglines: ['Two hundred million miles from home.', 'Survival is the only mission.'],
      motif: 'astronaut', ratings: ['PG-13'], weight: 2,
    },
    action: {
      titles: ['Shutdown Protocol {city}', '{last}: Rogue Asset', 'Retaliation Day', 'Retaliation in {city}', 'Exfil at Midnight', 'The {last} Contract', 'Twelve Hours in {city}'],
      taglines: ['He has a very particular set of problems.', 'No backup. No way out.'],
      motif: 'gun', ratings: ACTION, weight: 2, sequels: true,
    },
  },
  2020: {
    horror: {
      titles: ['Them in the Orchard', 'Mother of Thorns', 'The Hollowing', 'Saltmarsh Sunday', 'Underneath the Lake House', 'The Antler Wife', 'Grandmother\'s Teeth', 'The Tenant in the Wall', 'The {last} Rite', 'What Waits in {place}', 'The Visitors Upstairs'],
      taglines: ['Grief has a shape.', 'The family is the house. The house is hungry.', 'It was never just a dream.'],
      motif: 'symbol', ratings: ['R'], weight: 3, sequels: true,
    },
    multiverse: {
      titles: ['{first} Across Every Door', 'The Infinite {last}', 'All the {first}s at Once', 'Split Timeline', 'Everywhen', 'The {last} Variant', '{first} and the Thousand Doors'],
      taglines: ['Every choice opens a door.', 'One woman. Every possible life.'],
      motif: 'portal', ratings: ['PG-13'], weight: 2, sequels: true,
    },
    drama: {
      titles: ['The {last} Year', 'Winter at the {last} Farm', 'Summer in {place}', 'The Last Summer at {place}', 'What the River Keeps', 'A Quiet Inheritance', 'Letters to {first}', 'The Ferryman\'s Son', 'The Orchard Keeper\'s Daughter'],
      taglines: ['A film about everything we leave behind.', 'Winner of every award it was entered for.'],
      motif: 'portrait', ratings: GENERAL, weight: 2,
    },
    animated: {
      titles: ['Pocket Dragons', 'The Moon Bakery', 'The {critter} Kingdom', '{critter} Express', 'Sprout and the Giant Seed', 'The Lost Sock Society', 'Turtle Express', 'Hamster Heist 3000'],
      taglines: ['Big hearts come in small shells.', 'Fun for the whole family!'],
      motif: 'cartoon', ratings: FAMILY, weight: 2, sequels: true,
    },
    biopic: {
      titles: ['{first} {last}: The Comeback', 'The {last} Sound', 'Queen of the Roller Rink', 'The Inventor of Tuesday', 'Ballad of {first}'],
      taglines: ['Based on a story that is almost true.', 'Before the legend, there was a kid with a dream.'],
      motif: 'microphone', ratings: GENERAL, weight: 1,
    },
    action: {
      titles: ['{last}: Last Protocol', 'Blackout Run', 'Hard Reboot', 'Crossfire Day', 'Night Shift in {city}', 'Courier Seven', '{last}: Zero Hour', 'Escape from {city}'],
      taglines: ['One night. One city. No signal.', 'Retirement was never an option.'],
      motif: 'hero', ratings: ACTION, weight: 2, sequels: true,
    },
  },
};

/** Four-colour palettes per decade (background, main, accent, lettering). */
const PALETTES: Record<Decade, readonly [string, string, string, string][]> = {
  1950: [['#f2d24a', '#2a6a3c', '#d8382c', '#1e1a18'], ['#e8e0c8', '#c0302a', '#2c5c9a', '#f6c63c'], ['#1c2c4c', '#e86c2a', '#f2e6b0', '#e8382c'], ['#7cc0c8', '#4a2a5c', '#f0e04a', '#c02828']],
  1960: [['#c8a46a', '#7a2a1c', '#e8c84a', '#2a1a10'], ['#1a1a1a', '#e8e0d0', '#c83a2a', '#f0c030'], ['#f0e8d8', '#2a6aa0', '#e85a8a', '#1a2a4a'], ['#3a5a2a', '#e8d070', '#a0301c', '#f8f0e0']],
  1970: [['#2a1a10', '#e87a2a', '#f8d04a', '#f0e8d0'], ['#e8c890', '#8a3a1a', '#3a6a7a', '#2a1a10'], ['#101828', '#c0c8d8', '#e84a2a', '#f8d860'], ['#f0a040', '#5a2a1a', '#f8e8c0', '#2a3a5a']],
  1980: [['#0c0618', '#ff2a8a', '#2ae0ff', '#f8f0ff'], ['#08101c', '#a0e0ff', '#ff7a1a', '#e8e8f0'], ['#1a0a2a', '#ffcc2a', '#ff3a3a', '#f0f0f0'], ['#000000', '#3aff8a', '#c03aff', '#e8fff0']],
  1990: [['#0a0e18', '#d0d8e0', '#c02020', '#f8f8f8'], ['#f0e8d0', '#1a3a6a', '#e8a020', '#101010'], ['#202a20', '#c8b080', '#7a1a1a', '#f0f0e8'], ['#1a2a4a', '#f0c840', '#e8e8f0', '#ffffff']],
  2000: [['#0a1424', '#ff8a2a', '#3aa0ff', '#f8f8f8'], ['#1a1008', '#f0a030', '#ffe8a0', '#ffffff'], ['#0c1c2c', '#7ad0ff', '#ff5a1a', '#f0f8ff'], ['#2a2a30', '#c8c8c0', '#e83a2a', '#ffffff']],
  2010: [['#0a2830', '#ff9a3a', '#2ad0c8', '#f0f8f8'], ['#081820', '#3ac0d0', '#f07a2a', '#ffffff'], ['#101018', '#e8e8f0', '#e8b030', '#ffffff'], ['#1c2a38', '#f0c890', '#38a8c0', '#f8f8f8']],
  2020: [['#e8dcc8', '#2a2a28', '#c8402a', '#1a1a18'], ['#1a3a34', '#e8d8b0', '#e87a5a', '#f8f0e0'], ['#f0c8c0', '#3a2a5a', '#f8f0e8', '#2a2040'], ['#101010', '#d8c070', '#f0f0f0', '#d8c070']],
};

const SUBTITLES = ['Reckoning', 'The Return', 'Second Wind', 'Resurrection', 'Legacy', 'Aftermath', 'The Final Chapter', 'Revelations', 'Bloodlines', 'Uprising'];
const NUMERALS = ['', '', 'II', 'III', 'IV', '5', '6', '7'];

// ------------------------------------------------------------------------------------------------ generator
export const decadeOf = (year: number): Decade =>
  Math.min(2020, Math.max(1950, Math.floor(year / 10) * 10)) as Decade;

/** The rating a film of [genre] released in [year] carries, or undefined before ratings began (1968). */
function ratingOf(r: Rng, spec: GenreSpec, year: number): string | undefined {
  if (year < 1968) return undefined;
  const pool = spec.ratings.length ? spec.ratings : GENERAL;
  let x = pick(r, pool);
  if (x === 'PG-13' && year < 1984) x = r() < 0.5 ? 'PG' : 'R'; // the middle mark came in 1984
  return x;
}

const fill = (r: Rng, pattern: string, words: Record<string, readonly string[]>, chosen?: string[]): string =>
  pattern.replace(/\{(\w+)\}/g, (_, k: string) => { const w = pick(r, words[k] ?? SHARED[k] ?? [k]); chosen?.push(w); return w; });

const weighted = (r: Rng, genres: [string, GenreSpec][]): [string, GenreSpec] => {
  let t = r() * genres.reduce((s, [, g]) => s + (g.weight ?? 1), 0);
  for (const e of genres) { t -= e[1].weight ?? 1; if (t < 0) return e; }
  return genres[genres.length - 1]!;
};

let catalogue: Film[][] = [];
const used = new Set<string>();
/** Each title's sequel count so far (the franchise's next number). */
const parts = new Map<string, number>();
/** The shape (genre and pattern) each title was made from, so a sequel and a new film of the same shape never share a year. */
const shapeOf = new Map<string, string>();

const titleKey = (t: string): string => t.toLowerCase().replace(/[^a-z0-9]/g, '');

function makeYear(year: number): Film[] {
  const dec = decadeOf(year);
  const genres = Object.entries(GENRES[dec]);
  const films: Film[] = [];
  /** Patterns and franchises already used this year: a year's bill never repeats a shape ("Burnout at X" twice). */
  const shapes = new Set<string>();
  /** Names and places already on this year's bill (no two pictures about "Rusty" in one year). */
  const names = new Set<string>();
  for (let i = 0; films.length < RELEASES; i++) {
    const r = rngFor(SEED, year, i);
    let genre: string, spec: GenreSpec, title: string, shape: string;
    const chosen: string[] = [];
    // from the 1970s on, some releases are sequels to a hit of a few years before (same genre family)
    const back = year >= 1972 ? catalogue[year - FIRST - 2 - Math.floor(r() * 5)] : undefined;
    const prev = back?.filter((f) => !/\b(II|III|IV|[2-9])\b|: /.test(f.title));
    const base = prev?.length && r() < (year >= 2000 ? 0.3 : 0.15) ? pick(r, prev) : undefined;
    const baseSpec = base && GENRES[decadeOf(base.year)][base.genre];
    if (base && baseSpec?.sequels) {
      genre = base.genre; spec = GENRES[dec][genre] ?? baseSpec;
      const n = (parts.get(base.title) ?? 1) + 1;
      shape = shapeOf.get(base.title)!;
      if (n > 5 || shapes.has(shape)) continue;
      title = year >= 1995 && r() < 0.5 ? `${base.title}: ${pick(r, SUBTITLES)}` : `${base.title} ${year >= 1985 && r() < 0.5 ? n : NUMERALS[n] ?? n}`;
    } else {
      [genre, spec] = weighted(r, genres);
      const pattern = pick(r, spec.titles);
      shape = `${genre}:${pattern}`;
      title = fill(r, pattern, spec.words ?? {}, chosen);
    }
    if (i > 4000) throw new Error(`films: the ${dec}s pools ran out of titles in ${year}`);
    if (used.has(titleKey(title)) || shapes.has(shape) || chosen.some((w) => names.has(w))) continue;
    used.add(titleKey(title));
    shapes.add(shape);
    chosen.forEach((w) => names.add(w));
    shapeOf.set(title, shape);
    if (base) parts.set(base.title, (parts.get(base.title) ?? 1) + 1);
    const words = { ...(spec.words ?? {}), title: [title] };
    const rating = ratingOf(r, spec, year);
    films.push({
      title, genre, year,
      tagline: fill(r, pick(r, spec.taglines), words),
      ...(rating ? { rating } : {}),
      star: `${pick(r, STAR_FIRST)} ${pick(r, STAR_LAST)}`,
      poster: {
        style: POSTER_STYLES[dec], motif: spec.motif,
        palette: [...pick(r, PALETTES[dec])] as Poster['palette'],
        seed: Math.floor(rand(SEED, year, i, 'poster') * 2 ** 31),
      },
    });
  }
  return films;
}

const STAR_FIRST = ['Rex', 'Gloria', 'Chet', 'Vivienne', 'Brock', 'Delphine', 'Lance', 'Marla', 'Tad', 'Corinne', 'Dirk', 'Odette', 'Grant', 'Sable', 'Rory', 'Imogen'];
const STAR_LAST = ['Stanwick', 'Carrow', 'Devane', 'Montclair', 'Halloran', 'Ashbury', 'Vale', 'Prescott', 'Lockridge', 'Fairweather', 'Kingsley', 'Duvall', 'Marchmont', 'Thackery'];

/** The year's releases (RELEASES films, made-up, never a title used before). Years before 1950 or after 2040 clamp. */
export function releases(year: number): readonly Film[] {
  const y = Math.min(LAST, Math.max(FIRST, Math.floor(year)));
  while (FIRST + catalogue.length <= y) catalogue.push(makeYear(FIRST + catalogue.length));
  return catalogue[y - FIRST]!;
}

/** Forget the generated catalogue (tests). */
export function resetFilms(): void { catalogue = []; used.clear(); parts.clear(); shapeOf.clear(); }
