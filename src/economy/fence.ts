import { rand } from '../sim/rng';
import type { Category } from './catalogue';

export type FencePerson = Readonly<{
  id: string;
  age: number;
  job: string;
  honesty: number;
  greed: number;
  sociability: number;
}>;

export type Fence = Readonly<{
  personId: string;
  cover: string;
  where: string;
  hours: readonly [start: number, end: number];
  categories: readonly Category[];
  honesty: number;
  greed: number;
}>;

const covers = [
  { cover: 'hardware clerk', where: 'behind the hardware store', categories: ['valuables', 'hand_tools', 'power_tools'] },
  { cover: 'repair clerk', where: 'at the repair shop back door', categories: ['valuables', 'electronics', 'instruments'] },
  { cover: 'secondhand dealer', where: 'at the secondhand shop back door', categories: ['valuables', 'clothing', 'instruments'] },
] as const;

export function townFences(people: readonly FencePerson[], seed: string): Fence[] {
  const size = people.length;
  if (rand(seed, 'fence_count') >= Math.min(.98, size / 120)) return [];
  const limit = size >= 80 && rand(seed, 'fence_second') < .55 ? 2 : 1;
  return people.filter(p => p.age >= 18 && p.honesty <= .45 && p.greed >= .5)
    .map(person => ({
      person,
      score: person.greed - person.honesty + .2 * person.sociability + .3 * rand(seed, 'fence_rank', person.id),
    }))
    .sort((a, b) => b.score - a.score || (a.person.id < b.person.id ? -1 : Number(a.person.id > b.person.id)))
    .slice(0, limit)
    .map(({ person }) => {
      const preferred = covers.find(cover => cover.cover === person.job.trim().toLowerCase());
      const cover = preferred ?? covers[Math.floor(rand(seed, 'fence_cover', person.id) * covers.length)]!;
      return { ...cover, personId: person.id, hours: [20, 2], honesty: person.honesty, greed: person.greed };
    });
}
