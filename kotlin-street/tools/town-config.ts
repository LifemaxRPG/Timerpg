// The one town the walkable game is built from (kotlin-street/docs/TOWN.md): every whole-town exporter (street.json v2,
// dialogue, interiors, building assets) reads it from here, so switching town is a change in one place. `gamma` is a
// commuter suburb with a river, all five neighbourhoods and a civic avenue of landmarks; `eta` stays the v1 two-street
// town (tools/town-streets.ts) until the app is wired to the whole town.
export const TOWN = { seed: 'gamma', year: 2025 } as const;

// The world the town stands in (src/world/rail.ts RailWorld): its seed lays the other towns, the railroads and the
// countryside round it, with `home` (the town above) at cell 0,0. Picked for gamma's railroad: the main line comes round
// the south of town past a depot by the west end, and crosses the road out of town to the east.
export const WORLD = { seed: 'world-6', home: TOWN.seed } as const;
