import { foundedOf } from './archetype';
import type { Person, World } from './types';
import { townName } from '../world/names';

// WHERE A PERSON IS FROM. The simulation already knows it from how each person came to be: the founding generation
// (`gen:*`) was in town before the game's start, children (`<mother>><year>`) were born here, and newcomers
// (`imm:<year>:<n>`) moved in that year. Where a newcomer came from is a name made from the town's seed and their id,
// the same in every timeline. (A placeholder until the world map places towns: then it should be a real town on it.)

export interface Origin {
  /** born: born and raised here; settled: came when the town was founded; moved: moved in from elsewhere. */
  how: 'born' | 'settled' | 'moved';
  /** The year they arrived (moved, settled) or were born (born). */
  year: number;
  /** Where they came from (moved only). */
  from?: string;
}

export function originOf(w: World, p: Person): Origin {
  const imm = /^imm:(\d+):/.exec(p.id);
  if (imm) {
    const home = townName(w.seed);
    // another town's name, never this one's
    let from = townName(`${w.seed}~from~${p.id}`);
    for (let k = 1; from === home && k < 8; k++) from = townName(`${w.seed}~from~${p.id}~${k}`);
    return { how: 'moved', year: Number(imm[1]), from };
  }
  // the founding generation: older than the town itself came when it was founded
  const founded = foundedOf(w.seed);
  if (p.birthYear < founded) return { how: 'settled', year: founded };
  return { how: 'born', year: p.birthYear };
}

/** The origin as the fact sheet says it, after "a woman/man ": "born and raised in X", "who moved to X from Y in 2021". */
export function originWords(w: World, p: Person, town: string): string {
  const o = originOf(w, p);
  if (o.how === 'moved') return `who moved to ${town} from ${o.from} in ${o.year}`;
  if (o.how === 'settled') return `who settled in ${town} when it was founded in ${o.year}`;
  return `born and raised in ${town}`;
}
