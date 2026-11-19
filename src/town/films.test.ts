import { describe, expect, it } from 'vitest';
import { DECADES, POSTER_STYLES, RELEASES, decadeOf, releases, resetFilms } from './films';
import { CINEMA_POPULATION, cinemaEra, cinemaName, cinemaProgram, hasCinema, peakPopulation, showingCount } from './cinema';

// Famous real films: none may ever be a title (or a sequel's base title). Compared without case, punctuation or spaces.
const REAL = [
  // 1930s-1950s
  'Casablanca', 'Citizen Kane', 'Gone with the Wind', 'The Wizard of Oz', 'King Kong', 'Frankenstein', 'Dracula', 'The Mummy',
  'The Wolf Man', 'Bride of Frankenstein', 'The Maltese Falcon', 'Double Indemnity', 'Sunset Boulevard', 'All About Eve',
  'Singin in the Rain', 'An American in Paris', 'High Noon', 'Shane', 'The Searchers', 'Rio Bravo', 'Vertigo', 'Rear Window',
  'North by Northwest', 'Psycho', 'The Day the Earth Stood Still', 'Invaders from Mars', 'Forbidden Planet', 'Them', 'The Thing',
  'The Thing from Another World', 'Creature from the Black Lagoon', 'Revenge of the Creature', 'The Blob', 'The Fly',
  'Invasion of the Body Snatchers', 'War of the Worlds', 'It Came from Outer Space', 'It Came from Beneath the Sea',
  'Attack of the 50 Foot Woman', 'Attack of the Crab Monsters', 'Godzilla', 'Rodan', 'Tarantula', 'The Incredible Shrinking Man',
  'Earth vs the Flying Saucers', 'Plan 9 from Outer Space', 'Rocketship XM', 'Destination Moon', 'When Worlds Collide',
  'The Ten Commandments', 'Ben-Hur', 'Spartacus', 'Roman Holiday', 'Some Like It Hot', 'On the Waterfront', 'Rebel Without a Cause',
  'Guys and Dolls', 'Oklahoma', 'South Pacific', 'The King and I', 'Kiss Me Deadly', 'The Big Sleep', 'Touch of Evil', 'Gilda',
  'The Bridge on the River Kwai', 'From Here to Eternity', 'The Seven Year Itch', 'Johnny Guitar', 'Gunfight at the OK Corral',
  // 1960s
  'Lawrence of Arabia', 'Cleopatra', 'El Cid', 'The Fall of the Roman Empire', 'Jason and the Argonauts', 'Dr No', 'Goldfinger',
  'From Russia with Love', 'Thunderball', 'You Only Live Twice', 'The Ipcress File', 'Our Man Flint', 'Beach Party',
  'Beach Blanket Bingo', 'How to Stuff a Wild Bikini', 'The Sound of Music', 'My Fair Lady', 'Mary Poppins', 'West Side Story',
  'A Fistful of Dollars', 'For a Few Dollars More', 'The Good the Bad and the Ugly', 'Once Upon a Time in the West',
  'The Masque of the Red Death', 'Black Sunday', 'The Pit and the Pendulum', 'The Birds', 'The Graduate', 'Bonnie and Clyde',
  'Planet of the Apes', '2001 A Space Odyssey', 'Barbarella', 'Night of the Living Dead', 'Rosemarys Baby', 'The Great Escape',
  'The Dirty Dozen', 'Operation Petticoat', 'How to Steal a Million', 'How to Marry a Millionaire', 'The Magnificent Seven',
  // 1970s
  'Jaws', 'Star Wars', 'The Godfather', 'Rocky', 'Taxi Driver', 'Alien', 'The Exorcist', 'Halloween', 'Carrie', 'The Omen',
  'The Towering Inferno', 'The Poseidon Adventure', 'Earthquake', 'Airport', 'Airport 1975', 'Avalanche', 'Meteor',
  'Smokey and the Bandit', 'Vanishing Point', 'Two-Lane Blacktop', 'Duel', 'Convoy', 'The French Connection', 'Dirty Harry',
  'Magnum Force', 'Serpico', 'Badge 373', 'The Conversation', 'All the Presidents Men', 'Three Days of the Condor',
  'The Parallax View', 'Enter the Dragon', 'Fist of Fury', 'The Big Boss', 'Close Encounters of the Third Kind', 'Superman',
  'Grease', 'Saturday Night Fever', 'Apocalypse Now', 'Star Trek', 'Logans Run', 'Silent Running', 'Westworld', 'Mad Max',
  // 1980s
  'The Terminator', 'RoboCop', 'Blade Runner', 'Tron', 'Aliens', 'Predator', 'Die Hard', 'Lethal Weapon', '48 Hrs',
  'Beverly Hills Cop', 'Rambo', 'Commando', 'Top Gun', 'Back to the Future', 'Ghostbusters', 'ET', 'Gremlins', 'The Goonies',
  'Friday the 13th', 'A Nightmare on Elm Street', 'Prom Night', 'Graduation Day', 'Sleepaway Camp', 'My Bloody Valentine',
  'The Breakfast Club', 'Sixteen Candles', 'Ferris Buellers Day Off', 'Fast Times at Ridgemont High', 'Weird Science',
  'Conan the Barbarian', 'The NeverEnding Story', 'Labyrinth', 'The Dark Crystal', 'Willow', 'Dragonslayer', 'Legend',
  'Raiders of the Lost Ark', 'Indiana Jones', 'Batman', 'Who Framed Roger Rabbit', 'Dirty Dancing', 'Footloose', 'Flashdance',
  'Scarface', 'Platoon', 'Tango and Cash', 'Red Heat', 'Running Man', 'The Running Man', 'Total Recall', 'Cyborg',
  // 1990s
  'Jurassic Park', 'Titanic', 'The Matrix', 'Terminator 2', 'Speed', 'Twister', 'Independence Day', 'Armageddon', 'Deep Impact',
  'Volcano', 'Dantes Peak', 'Hard Target', 'Air Force One', 'Con Air', 'The Rock', 'Face Off', 'Point Break', 'Heat',
  'The Silence of the Lambs', 'Basic Instinct', 'Fatal Attraction', 'Seven', 'Se7en', 'The Usual Suspects', 'Pulp Fiction',
  'Fargo', 'Reservoir Dogs', 'Home Alone', 'Beethoven', 'Babe', 'Free Willy', 'Air Bud', 'The Lion King', 'Toy Story',
  'Sleepless in Seattle', 'Pretty Woman', 'Notting Hill', 'Youve Got Mail', 'When Harry Met Sally', 'Forrest Gump',
  'Scream', 'The Blair Witch Project', 'Men in Black', 'Mission Impossible', 'The Fugitive', 'Small Change', 'Double Jeopardy',
  // 2000s
  'The Lord of the Rings', 'Harry Potter', 'Pirates of the Caribbean', 'Gladiator', 'Troy', 'Kingdom of Heaven', 'The Last Legion',
  'Shrek', 'Finding Nemo', 'Cars', 'Ice Age', 'Madagascar', 'Happy Feet', 'Ratatouille', 'Up', 'Monsters Inc',
  'Paranormal Activity', 'Cloverfield', 'Rec', 'Oceans Eleven', 'The Italian Job', 'Inside Man', 'The Day After Tomorrow', '2012',
  'Sunshine', 'The Core', 'Superbad', 'The Hangover', 'Old School', 'Anchorman', 'Meet the Parents', 'The 40-Year-Old Virgin',
  'Dude Wheres My Car', 'Spider-Man', 'The Dark Knight', 'Iron Man', 'Transformers', 'Avatar',
  // 2010s
  'The Avengers', 'Avengers Endgame', 'Black Panther', 'Captain America', 'Thor', 'Guardians of the Galaxy', 'Wonder Woman',
  'Justice League', 'Man of Steel', 'Aquaman', 'The Hunger Games', 'Divergent', 'The Maze Runner', 'The Selection', 'Frozen',
  'Zootopia', 'Moana', 'Inside Out', 'Coco', 'It Follows', 'Get Out', 'Hereditary', 'The Conjuring', 'Insidious', 'Sinister',
  'Lights Out', 'Gravity', 'Interstellar', 'The Martian', 'Arrival', 'Taken', 'John Wick', 'Kill Switch', 'Extraction', 'Gridlock',
  'Mad Max Fury Road', 'Inception', 'Star Wars The Force Awakens', 'Jurassic World', 'Dunkirk',
  // 2020s
  'Everything Everywhere All at Once', 'Across the Spider-Verse', 'Oppenheimer', 'Barbie', 'Dune', 'Top Gun Maverick', 'Nope',
  'A Quiet Place', 'Midsommar', 'The Witch', 'Smile', 'Barbarian', 'Talk to Me', 'Encanto', 'Soul', 'Turning Red', 'Elemental',
  'Wicked', 'Tenet', 'The Batman', 'Glass Onion', 'Knives Out', 'The Courier', 'Fawn', 'Paper Birds', 'Small Hours',
];
// Real studios, chains, franchises and characters: none may appear in any title, tagline, star or theater name.
const BRANDS = /\b(disney|pixar|dreamworks|warner|paramount|universal|marvel|dc comics|lucasfilm|mgm|metro goldwyn|20th century|fox|netflix|columbia|amc|regal|cinemark|odeon|loews|bond|007|batman|superman|spider-?man|jedi|godzilla|kong|dracula|frankenstein|tarzan|zorro|rocky|rambo|terminator|avengers|hulk|potter|hobbit|oz|imax|dolby|technicolor|cinemascope|oscar)\b/i;

const norm = (t: string): string => t.toLowerCase().replace(/[^a-z0-9]/g, '');
const real = new Set(REAL.map(norm));
/** A title with a sequel number or subtitle stripped. */
const baseOf = (t: string): string => t.replace(/: .*$/, '').replace(/ (II|III|IV|V|\d+)$/, '');

const ALL = (): ReturnType<typeof releases>[number][] => {
  const out = [];
  for (let y = 1950; y <= 2040; y++) out.push(...releases(y));
  return out;
};

describe('films', () => {
  it('is deterministic, also after starting over', () => {
    const a = JSON.stringify(ALL());
    resetFilms();
    expect(JSON.stringify(ALL())).toBe(a);
  });

  it('never uses a title twice, ever, and fills every year', () => {
    const all = ALL();
    expect(all.length).toBe(91 * RELEASES);
    expect(new Set(all.map((f) => norm(f.title))).size).toBe(all.length);
  });

  it('never names a real film, studio, franchise or brand', () => {
    for (const f of ALL()) {
      expect(real.has(norm(f.title)), f.title).toBe(false);
      expect(real.has(norm(baseOf(f.title))), f.title).toBe(false);
      for (const text of [f.title, f.tagline, f.star]) expect(BRANDS.test(text), text).toBe(false);
    }
  });

  it('covers every decade in its own poster style, with 8+ posters a year, and rates films only from 1968', () => {
    for (const d of DECADES) {
      const films = Array.from({ length: 10 }, (_, i) => releases(d + i)).flat();
      expect(films.length).toBeGreaterThanOrEqual(8);
      expect(new Set(films.map((f) => f.poster.style))).toEqual(new Set([POSTER_STYLES[d]]));
      expect(new Set(films.map((f) => f.genre)).size, `${d}s genres`).toBeGreaterThanOrEqual(4);
      for (const f of films) {
        expect(decadeOf(f.year)).toBe(d);
        expect(f.poster.palette).toHaveLength(4);
        for (const c of f.poster.palette) expect(c).toMatch(/^#[0-9a-f]{6}$/);
        if (f.year < 1968) expect(f.rating, f.title).toBeUndefined();
        else expect(['G', 'PG', 'PG-13', 'R']).toContain(f.rating);
        if (f.year < 1984) expect(f.rating).not.toBe('PG-13');
      }
    }
  });
});

describe('the cinema (town)', () => {
  it('belongs to towns that reach CINEMA_POPULATION: gamma does, eta does not', () => {
    expect(peakPopulation('gamma')).toBeGreaterThanOrEqual(CINEMA_POPULATION);
    expect(hasCinema('gamma')).toBe(true);
    expect(peakPopulation('eta')).toBeLessThan(CINEMA_POPULATION);
    expect(hasCinema('eta')).toBe(false);
  });

  it('changes its front and screens with the times', () => {
    expect([1950, 1959, 1960, 1979, 1980, 1999, 2000, 2025].map(cinemaEra))
      .toEqual(['palace', 'palace', 'modern', 'modern', 'twin', 'twin', 'multiplex', 'multiplex']);
    expect(showingCount('gamma', 1955)).toBe(1);
    expect(showingCount('gamma', 1975)).toBe(2);
    expect(showingCount('gamma', 1985)).toBe(2);
    expect(showingCount('gamma', 1995)).toBe(3);
    expect(showingCount('gamma', 2025)).toBeGreaterThanOrEqual(6);
    expect(showingCount('gamma', 2025)).toBeLessThanOrEqual(8);
    expect(cinemaName('gamma', 1990)).toMatch(/ (Twin|Triple)$/);
  });

  it('shows the year\'s pictures, no title twice in a year, 8+ posters, the marquee spelling them', () => {
    for (const seed of ['gamma', 'alpha', 'nova']) {
      for (let year = 1950; year <= 2030; year++) {
        const p = cinemaProgram(seed, year);
        expect(p.nowShowing).toHaveLength(showingCount(seed, year));
        const titles = [...p.nowShowing, ...p.comingSoon].map((f) => f.title);
        expect(new Set(titles).size, `${seed} ${year}`).toBe(titles.length);
        expect(titles.length).toBeGreaterThanOrEqual(8);
        for (const f of p.nowShowing) expect(f.year).toBe(year);
        for (const f of p.comingSoon) expect(f.year).toBe(year + 1);
        for (const f of p.nowShowing) expect(p.marquee).toContain(f.title.toUpperCase());
        expect(BRANDS.test(p.name), p.name).toBe(false);
      }
    }
    // the same visit, the same bill; another year, another
    expect(cinemaProgram('gamma', 1957)).toEqual(cinemaProgram('gamma', 1957));
    expect(cinemaProgram('gamma', 1957).nowShowing[0]!.title).not.toBe(cinemaProgram('gamma', 1958).nowShowing[0]!.title);
  });
});
