// The one town the walkable game is built from (kotlin-street/docs/TOWN.md): every whole-town exporter (street.json v2,
// dialogue, interiors, building assets) reads it from here, so switching town is a change in one place. `gamma` is a
// commuter suburb with a river, all five neighbourhoods and a civic avenue of landmarks; `eta` stays the v1 two-street
// town (tools/town-streets.ts) until the app is wired to the whole town.
export const TOWN = { seed: 'gamma', year: 2025 } as const;
