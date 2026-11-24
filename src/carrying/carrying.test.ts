import { describe, expect, it } from 'vitest';
import { wardrobeOf } from '../clothes';
import { outfitContainers, place, searchFinds, storageContainer, visibility } from './index';

const watch = { id: 'watch-1', itemId: 'pocketwatch' };
const drill = { id: 'drill-1', itemId: 'brace' };
const bulky = { id: 'drill-2', itemId: 'corded_drill' };
const [pocket, coat] = outfitContainers({ garments: wardrobeOf(1952).filter(item => item.id === 'brown_coat') });
const trunk = storageContainer('trunk', 'player-trunk');
const seed = 'town';

describe('carrying', () => {
  it('enforces catalogue size and combined capacity without charging the same instance twice', () => {
    expect(place(bulky, pocket!)).toBeNull();
    expect(place({ id: 'unknown', itemId: 'missing' }, pocket!)).toBeNull();
    const contents = [place(watch, pocket!)!, place({ ...watch, id: 'watch-2' }, pocket!)!];
    expect(place({ ...watch, id: 'watch-3' }, pocket!, contents)).toBeNull();
    expect(place(watch, pocket!, contents)).toEqual(contents[0]);
    expect(outfitContainers({ garments: wardrobeOf(1952).filter(item => item.id === 'overalls') })[0]?.capacity).toBe(4);
  });

  it('shows a coat bulge without revealing the drill and shows items in hand', () => {
    const inCoat = place(drill, coat!)!;
    expect(visibility([inCoat, place(watch, pocket!)!])).toEqual({ bulge: true, visibleItems: [] });
    expect(visibility([place(bulky, storageContainer('hand', 'hand'))!])).toEqual({
      bulge: false, visibleItems: [bulky],
    });
    expect(visibility([place(watch, trunk)!])).toEqual({ bulge: false, visibleItems: [] });
  });

  it('limits pat-downs to pockets and coats and bag searches to the named bag', () => {
    const onPerson = [place(watch, pocket!)!, place(drill, coat!)!];
    const inBag = place({ ...watch, id: 'watch-2' }, storageContainer('briefcase', 'case'))!;
    const carried = [...onPerson, inBag, place(bulky, trunk)!];
    expect(searchFinds(carried, { kind: 'pat_down' }, 1952, seed)).toEqual(onPerson);
    expect(searchFinds(carried, { kind: 'bag', containerId: 'case' }, 1952, seed)).toEqual([inBag]);
  });

  it('gates car searches by era and keeps unrelated vehicles out', () => {
    const own = place(bulky, trunk)!;
    const other = place(watch, storageContainer('glovebox', 'other-car'))!;
    const search = { kind: 'car', containerIds: [trunk.id], authority: 'cause' } as const;
    expect(searchFinds([own, other], search, 1969, seed)).toEqual([]);
    expect(searchFinds([own, other], search, 1970, seed)).toEqual([own]);
    expect(searchFinds([own, other], { ...search, authority: 'warrant' }, 1952, seed)).toEqual([own]);
    expect(searchFinds([own, other], { ...search, authority: 'none' }, 2000, seed)).toEqual([]);
  });

  it('requires a room warrant and replays the same hiding-place discovery rolls', () => {
    const drawer = place(watch, storageContainer('drawer', 'drawer'))!;
    const hidden = place(drill, storageContainer('home_hiding_place', 'stash'))!;
    const search = { kind: 'room', containerIds: ['drawer', 'stash'], authority: 'warrant' } as const;
    const carried = [drawer, hidden];
    expect(searchFinds(carried, { ...search, authority: 'none' }, 1952, seed)).toEqual([]);
    const outcomes = Array.from({ length: 20 }, (_, index) => searchFinds(carried, search, 1952, String(index)));
    expect(outcomes.every(result => result.includes(drawer))).toBe(true);
    expect(outcomes.some(result => result.includes(hidden))).toBe(true);
    expect(outcomes.some(result => !result.includes(hidden))).toBe(true);
    expect(outcomes).toEqual(Array.from({ length: 20 }, (_, index) => searchFinds(carried, search, 1952, String(index))));
  });
});
